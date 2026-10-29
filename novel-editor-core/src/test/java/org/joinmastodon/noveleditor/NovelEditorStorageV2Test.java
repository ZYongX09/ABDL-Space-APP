package org.joinmastodon.noveleditor;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class NovelEditorStorageV2Test{
	private final Context context=ApplicationProvider.getApplicationContext();
	private String account(){return "editor-test-"+UUID.randomUUID();}

	@Test public void upgradingActualV1DatabaseRetainsDraftJournalSnapshotsAndOldImport(){
		String account=account(), name=NovelEditorStorage.databaseName(account);
		EditorModels.CreatedWork created;
		// Build the predecessor schema and data, then exercise SQLiteOpenHelper's real version upgrade.
		try(var storage=new NovelEditorStorage(context,account)){
			created=storage.createWork("旧作品","旧简介","fiction");
			storage.checkpoint(created.chapterId(),"旧正文",1,2,37,true);
			storage.snapshot(created.chapterId(),"before-upgrade");
			storage.appendJournal(created.chapterId(),2,3,0,"继续");
			storage.createImportJob("old.txt","text/plain","/tmp/source",8,"hash");
			SQLiteDatabase db=storage.getWritableDatabase();
			for(String table:List.of("editor_app_submission_chapters","editor_app_submissions","editor_app_chapters","editor_app_metadata","editor_app_reading","editor_app_annotations"))db.execSQL("DROP TABLE "+table);
			db.setVersion(1);
		}
		try(var upgraded=new NovelEditorStorage(context,account)){
			assertEquals(3,upgraded.getReadableDatabase().getVersion());
			assertEquals("旧作品",upgraded.getWork(created.workId()).title());
			assertEquals("旧正文继续",upgraded.loadDraft(created.chapterId()).content());
			assertEquals(37,upgraded.loadDraft(created.chapterId()).scrollY());
			try(var rows=upgraded.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_snapshots",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
			try(var rows=upgraded.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_import_jobs",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
			upgraded.putAppMetadata(created.workId(),"{}","server","release");assertEquals("server",upgraded.appMetadata(created.workId()).serverId());
		}
		context.deleteDatabase(name);
	}

	@Test public void titleBodyCursorScrollCheckpointIsAtomicOnDiskFailureAndGenerationConflict(){
		String account=account();
		try(var s=new NovelEditorStorage(context,account)){
			var work=s.createWork("作品","简介","fiction");
			s.checkpointWithTitle(work.chapterId(),1,"原题","原正文",1,2,37);
			s.getWritableDatabase().execSQL("CREATE TRIGGER fail_save BEFORE UPDATE ON editor_chapters BEGIN SELECT RAISE(ABORT,'disk full'); END");
			assertThrows(Exception.class,()->s.checkpointWithTitle(work.chapterId(),2,"新题","新正文",2,3,72));
			assertEquals("原题",s.getChapter(work.chapterId()).title());assertEquals("原正文",s.loadDraft(work.chapterId()).content());
			s.getWritableDatabase().execSQL("DROP TRIGGER fail_save");
			assertThrows(IllegalStateException.class,()->s.checkpointWithTitle(work.chapterId(),1,"过期题","过期文",0,0,0));
			assertEquals("原题",s.getChapter(work.chapterId()).title());
			s.checkpointWithTitle(work.chapterId(),2,"新题","新正文",2,3,72);
		}
		try(var reopened=new NovelEditorStorage(context,account)){
			var chapter=reopened.structure(reopened.listWorks(false).get(0).id()).chapters().get(0);
			var draft=reopened.loadDraft(chapter.id());assertEquals("新题",chapter.title());assertEquals("新正文",draft.content());assertEquals(2,draft.selectionStart());assertEquals(3,draft.selectionEnd());assertEquals(72,draft.scrollY());
		}
	}

	@Test public void immutableSelectionIsCanonicalAndDoesNotReadOrFreezeUnselectedDraft(){
		try(var s=new NovelEditorStorage(context,account())){
			var work=s.createWork("作品","简介","fiction");String a=work.chapterId(),b=s.addChapter(work.workId(),work.volumeId(),"二").id(),c=s.addChapter(work.workId(),work.volumeId(),"三").id();
			s.checkpoint(a,"选中一",0,0,0,true);s.checkpoint(b,"私密未选",0,0,0,true);s.checkpoint(c,"选中三",0,0,0,true);
			String id=s.prepareAppSubmission(work.workId(),"{}",List.of(c,a));assertEquals(List.of(a,c),s.appSubmissionChapterIds(id));
			assertEquals(0,s.appSubmissionChapter(id,0).sortOrder());assertEquals(2,s.appSubmissionChapter(id,1).sortOrder());
			s.checkpoint(a,"提交后改写",0,0,0,true);s.renameChapter(c,"后改标题");
			assertEquals("选中一",s.appSubmissionChapter(id,0).body());assertEquals("三",s.appSubmissionChapter(id,1).title());
			assertThrows(IllegalArgumentException.class,()->s.prepareAppSubmission(work.workId(),"{}",List.of(a,a)));
			assertThrows(IllegalArgumentException.class,()->s.prepareAppSubmission(work.workId(),"{}",java.util.Collections.nCopies(1001,a)));
		}
	}

	@Test public void subsetRetainsRemoteSortSoUnselectedApprovedChapterCannotCollide(){
		try(var s=new NovelEditorStorage(context,account())){
			var work=s.createWork("作品","简介","fiction");String local=work.chapterId();s.checkpoint(local,"本地新章",0,0,0,true);
			s.ensureRemoteChapter(work.workId(),"approved-a","远端一",10,4,"published",null,null);
			s.ensureRemoteChapter(work.workId(),"approved-b","远端二",20,4,"published",null,null);
			s.hydrateRemoteChapter("approved-a","远端正文",4);
			String id=s.prepareAppSubmission(work.workId(),"{}",List.of(local,"approved-a"));
			assertEquals(21,s.appSubmissionChapter(id,0).sortOrder());assertEquals(10,s.appSubmissionChapter(id,1).sortOrder());
			assertEquals("REMOTE_STUB",s.getChapter("approved-b").state());
		}
	}

	@Test public void submissionAcceptsMoreThanTenMiBButRollsBackAboveTwentyMiBUtf8(){
		try(var s=new NovelEditorStorage(context,account())){
			var work=s.createWork("作品","简介","fiction");var ids=new ArrayList<String>();String body="中".repeat(250000);
			for(int i=0;i<28;i++){String id=i==0?work.chapterId():s.addChapter(work.workId(),work.volumeId(),"章"+i).id();s.checkpoint(id,body,0,0,0,true);ids.add(id);}
			String accepted=s.prepareAppSubmission(work.workId(),"{}",ids.subList(0,15));assertEquals(15,s.appSubmissionChapterIds(accepted).size());
			assertThrows(IllegalArgumentException.class,()->s.prepareAppSubmission(work.workId(),"{}",ids));
			try(var rows=s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_app_submissions",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
		}
	}
}
