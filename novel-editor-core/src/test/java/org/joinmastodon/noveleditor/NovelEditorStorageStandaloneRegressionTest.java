package org.joinmastodon.noveleditor;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual predecessor schemas and data, independent of the original V2 fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class NovelEditorStorageStandaloneRegressionTest{
    private final Context context=ApplicationProvider.getApplicationContext();

    @Test public void actualV2UpgradeAddsOwnerColumnsWithoutTouchingOtherWorksOrFrozenRetry(){
        String account="storage-v2-regression-"+UUID.randomUUID(), name=NovelEditorStorage.databaseName(account);
        EditorModels.CreatedWork first,other;String submission,job;
        try(var s=new NovelEditorStorage(context,account)){
            first=s.createWork("旧投稿作品","旧简介","fiction");other=s.createWork("另一本作品","不许改变","fiction");
            s.checkpoint(first.chapterId(),"原文",1,2,83,true);s.snapshot(first.chapterId(),"before-v2-upgrade");s.appendJournal(first.chapterId(),2,2,0,"尾段");
            s.checkpoint(other.chapterId(),"别的作品未选正文",3,5,67,true);
            s.putAppMetadata(first.workId(),"{\"title\":\"旧本机资料\"}","server-old","release-old");s.putAppMetadata(other.workId(),"{\"title\":\"其他作品\"}","server-other",null);
            s.putRemoteChapter(first.chapterId(),"pending","server-attempt",null,2);
            submission=s.prepareAppSubmission(first.workId(),"{\"client_work_id\":\"frozen-original-id\",\"base_release_id\":\"release-old\"}",List.of(first.chapterId()));
            s.updateAppSubmission(submission,"server-attempt","uploading",null);
            job=s.createImportJob("old-import.txt","text/plain","/local/old",20,"source-hash").id();
            s.putReadingMark("public:untouched","chapter-mark",7,"reader-release");s.addReadingAnnotation("public:untouched","chapter-mark",7,"私人笔记");
            SQLiteDatabase db=s.getWritableDatabase();
            // Reconstruct precisely the old metadata table, then let SQLiteOpenHelper upgrade version 2.
            db.execSQL("ALTER TABLE editor_app_metadata RENAME TO editor_app_metadata_new");
            db.execSQL("CREATE TABLE editor_app_metadata(work_id TEXT PRIMARY KEY REFERENCES editor_works(id) ON DELETE CASCADE,payload TEXT NOT NULL,server_work_id TEXT UNIQUE,base_release_id TEXT)");
            db.execSQL("INSERT INTO editor_app_metadata SELECT work_id,payload,server_work_id,base_release_id FROM editor_app_metadata_new");
            db.execSQL("DROP TABLE editor_app_metadata_new");db.setVersion(2);
        }
        try(var s=new NovelEditorStorage(context,account)){
            assertEquals(3,s.getReadableDatabase().getVersion());
            assertEquals("原文尾段",s.loadDraft(first.chapterId()).content());assertEquals(83,s.loadDraft(first.chapterId()).scrollY());
            assertEquals("别的作品未选正文",s.loadDraft(other.chapterId()).content());assertEquals(67,s.loadDraft(other.chapterId()).scrollY());assertEquals("另一本作品",s.getWork(other.workId()).title());
            var before=s.appMetadata(first.workId());assertNotNull(before);assertEquals("server-old",before.serverId());assertEquals("release-old",before.releaseId());assertNull(before.clientWorkId());assertNull(before.ownerPayload());assertNull(before.ownerConflict());
            assertTrue(s.appSubmission(submission).metadata().contains("\"client_work_id\":\"frozen-original-id\""));
            assertEquals("uploading",s.appSubmission(submission).state());assertEquals(submission,s.pendingAppSubmission(first.workId()));assertEquals("原文尾段",s.appSubmissionChapter(submission,0).body());
            assertEquals("pending",s.remoteChapter(first.chapterId()).status());assertEquals("server-attempt",s.remoteChapter(first.chapterId()).submissionId());
            assertNotNull(s.getImportJob(job));assertEquals(7,s.readingMark("public:untouched").page());assertEquals("私人笔记",s.readingAnnotations("public:untouched").get(0).note());
            try(var rows=s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_snapshots",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
            try(var rows=s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_edit_journal",null)){assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));}
            s.putOwnerState(first.workId(),"server-client-original","{\"release_id\":\"release-new\"}","explicit conflict");
            s.putAppMetadata(first.workId(),"{\"title\":\"本机后来修改\"}","server-old","release-new");
            var after=s.appMetadata(first.workId());assertEquals("server-client-original",after.clientWorkId());assertEquals("explicit conflict",after.ownerConflict());
            assertThrows(IllegalStateException.class,()->s.putOwnerState(first.workId(),"different-owner-client","{}",null));
            assertThrows(IllegalStateException.class,()->s.acknowledgeOwnerConflict(first.workId(),"outdated-owner-payload"));
            assertEquals("server-other",s.appMetadata(other.workId()).serverId());assertNull(s.appMetadata(other.workId()).clientWorkId());assertEquals("{\"title\":\"其他作品\"}",s.appMetadata(other.workId()).payload());
            assertEquals("{\"client_work_id\":\"frozen-original-id\",\"base_release_id\":\"release-old\"}",s.appSubmission(submission).metadata());
        }finally{context.deleteDatabase(name);}
    }

    @Test public void v1UpgradeCreatesNewOwnerColumnsAndPreservesJournalSnapshotImportAndDraft(){
        String account="storage-v1-regression-"+UUID.randomUUID(),name=NovelEditorStorage.databaseName(account);EditorModels.CreatedWork work;
        try(var s=new NovelEditorStorage(context,account)){
            work=s.createWork("V1旧作品","旧简介","fiction");s.checkpoint(work.chapterId(),"旧稿",1,2,55,true);s.snapshot(work.chapterId(),"keep");s.appendJournal(work.chapterId(),2,2,0,"续写");s.createImportJob("private.txt","text/plain","/local/private",12,"source-hash");
            SQLiteDatabase db=s.getWritableDatabase();for(String table:List.of("editor_app_submission_chapters","editor_app_submissions","editor_app_chapters","editor_app_metadata","editor_app_reading","editor_app_annotations"))db.execSQL("DROP TABLE "+table);db.setVersion(1);
        }
        try(var s=new NovelEditorStorage(context,account)){
            assertEquals(3,s.getReadableDatabase().getVersion());assertEquals("旧稿续写",s.loadDraft(work.chapterId()).content());assertEquals(55,s.loadDraft(work.chapterId()).scrollY());
            s.putAppMetadata(work.workId(),"{}","server",null);s.putOwnerState(work.workId(),"original-client","{}",null);assertEquals("original-client",s.appMetadata(work.workId()).clientWorkId());
            try(var c=s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_import_jobs",null)){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
            try(var c=s.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM editor_snapshots",null)){assertTrue(c.moveToFirst());assertEquals(1,c.getInt(0));}
        }finally{context.deleteDatabase(name);}
    }

    @Test public void explicitSelectedSnapshotAdvancesRemoteGenerationWithoutMutatingLocalGenerationOrOtherChapter(){
        String account="storage-selection-regression-"+UUID.randomUUID();
        try(var s=new NovelEditorStorage(context,account)){
            var work=s.createWork("选章","简介","fiction");s.checkpoint(work.chapterId(),"本机所选稿",2,4,73,true);
            String other=s.addChapter(work.workId(),work.volumeId(),"其他私密稿").id();s.checkpoint(other,"未选不能上传",0,1,54,true);
            s.ensureRemoteChapter(work.workId(),work.chapterId(),"远端旧题",3,90,"published","approved-release",null);
            long localGeneration=s.getChapter(work.chapterId()).currentGeneration();
            String submission=s.prepareAppSubmission(work.workId(),"{}",List.of(work.chapterId()));
            assertEquals(91,s.appSubmissionChapter(submission,0).generation());assertEquals(localGeneration,s.getChapter(work.chapterId()).currentGeneration());assertEquals("本机所选稿",s.appSubmissionChapter(submission,0).body());
            assertEquals(List.of(work.chapterId()),s.appSubmissionChapterIds(submission));assertEquals("未选不能上传",s.loadDraft(other).content());assertEquals(73,s.loadDraft(work.chapterId()).scrollY());
            s.updateAppSubmission(submission,"remote-submission","approved","{}");assertNull(s.pendingAppSubmission(work.workId()));assertNull(s.cancellableAppSubmission(work.workId()));
        }finally{context.deleteDatabase(NovelEditorStorage.databaseName(account));}
    }
}
