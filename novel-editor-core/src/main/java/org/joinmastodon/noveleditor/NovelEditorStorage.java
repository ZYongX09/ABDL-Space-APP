package org.joinmastodon.noveleditor;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.joinmastodon.noveleditor.EditorModels.*;

/** Local-first author workspace. Directory queries never read chapter content. */
public final class NovelEditorStorage extends SQLiteOpenHelper implements java.io.Closeable{
	private static final int VERSION=3;
	private final String accountId;

	public NovelEditorStorage(Context context, String accountId){
		super(context.getApplicationContext(), "novel_editor_"+EditorText.sha256(Objects.requireNonNull(accountId)).substring(0, 24)+".db", null, VERSION);
		this.accountId=accountId;
		setWriteAheadLoggingEnabled(true);
	}

	@Override public void onConfigure(SQLiteDatabase db){
		super.onConfigure(db);
		db.setForeignKeyConstraintsEnabled(true);
	}

	@Override public void onCreate(SQLiteDatabase db){
		db.execSQL("CREATE TABLE editor_works(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',category TEXT NOT NULL DEFAULT 'fiction',declared_rating TEXT NOT NULL DEFAULT 'all_ages',content_warning TEXT NOT NULL DEFAULT '',state TEXT NOT NULL DEFAULT 'LOCAL_DRAFT',source_format TEXT,local_version INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,last_opened_at INTEGER NOT NULL,deleted_at INTEGER)");
		db.execSQL("CREATE INDEX editor_works_list ON editor_works(account_id,deleted_at,updated_at DESC)");
		db.execSQL("CREATE TABLE editor_volumes(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES editor_works(id) ON DELETE CASCADE,title TEXT NOT NULL,sort_key INTEGER NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER)");
		db.execSQL("CREATE INDEX editor_volumes_list ON editor_volumes(work_id,deleted_at,sort_key)");
		db.execSQL("CREATE TABLE editor_chapters(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES editor_works(id) ON DELETE CASCADE,volume_id TEXT NOT NULL REFERENCES editor_volumes(id) ON DELETE CASCADE,title TEXT NOT NULL,sort_key INTEGER NOT NULL,current_generation INTEGER NOT NULL DEFAULT 1,content_length INTEGER NOT NULL DEFAULT 0,content_sha256 TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'LOCAL_DRAFT',created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,deleted_at INTEGER)");
		db.execSQL("CREATE INDEX editor_chapters_list ON editor_chapters(work_id,volume_id,deleted_at,sort_key)");
		db.execSQL("CREATE TABLE editor_drafts(chapter_id TEXT PRIMARY KEY REFERENCES editor_chapters(id) ON DELETE CASCADE,content TEXT NOT NULL,generation INTEGER NOT NULL,sha256 TEXT NOT NULL,dirty INTEGER NOT NULL CHECK(dirty IN(0,1)),selection_start INTEGER NOT NULL DEFAULT 0,selection_end INTEGER NOT NULL DEFAULT 0,scroll_y INTEGER NOT NULL DEFAULT 0,saved_at INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE editor_edit_journal(chapter_id TEXT NOT NULL REFERENCES editor_chapters(id) ON DELETE CASCADE,seq INTEGER NOT NULL,base_generation INTEGER NOT NULL,start_offset INTEGER NOT NULL,deleted_count INTEGER NOT NULL,inserted_text TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(chapter_id,seq))");
		db.execSQL("CREATE TABLE editor_snapshots(id TEXT PRIMARY KEY,chapter_id TEXT NOT NULL REFERENCES editor_chapters(id) ON DELETE CASCADE,generation INTEGER NOT NULL,content TEXT NOT NULL,sha256 TEXT NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX editor_snapshots_recent ON editor_snapshots(chapter_id,created_at DESC)");
		db.execSQL("CREATE TABLE editor_import_jobs(id TEXT PRIMARY KEY,account_id TEXT NOT NULL,display_name TEXT,mime_type TEXT,format TEXT,staged_path TEXT,source_size INTEGER,source_sha256 TEXT,phase TEXT NOT NULL,state TEXT NOT NULL,bytes_done INTEGER NOT NULL DEFAULT 0,bytes_total INTEGER NOT NULL DEFAULT 0,items_done INTEGER NOT NULL DEFAULT 0,items_total INTEGER NOT NULL DEFAULT 0,encoding TEXT,error_code TEXT,error_detail TEXT,warning_count INTEGER NOT NULL DEFAULT 0,result_work_id TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX editor_import_jobs_list ON editor_import_jobs(account_id,state,updated_at DESC)");
		db.execSQL("CREATE TABLE editor_import_chapters(job_id TEXT NOT NULL REFERENCES editor_import_jobs(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,suggested_title TEXT NOT NULL,content TEXT NOT NULL,source_anchor TEXT,content_length INTEGER NOT NULL,sha256 TEXT NOT NULL,included INTEGER NOT NULL DEFAULT 1 CHECK(included IN(0,1)),PRIMARY KEY(job_id,ordinal))");
		db.execSQL("CREATE TABLE editor_publish_jobs(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES editor_works(id),snapshot_version INTEGER NOT NULL,state TEXT NOT NULL,operation_id TEXT NOT NULL UNIQUE,request_hash TEXT,last_error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX editor_publish_jobs_list ON editor_publish_jobs(work_id,state,updated_at DESC)");
		db.execSQL("CREATE TABLE editor_sync_operations(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES editor_works(id),entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,operation TEXT NOT NULL,generation INTEGER NOT NULL,payload_hash TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,last_error TEXT,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX editor_sync_pending ON editor_sync_operations(state,updated_at)");
		createAppTables(db);
	}

	private static void createAppTables(SQLiteDatabase db){
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_metadata(work_id TEXT PRIMARY KEY REFERENCES editor_works(id) ON DELETE CASCADE,payload TEXT NOT NULL,server_work_id TEXT UNIQUE,base_release_id TEXT,server_client_work_id TEXT,owner_payload TEXT,owner_conflict TEXT)");
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_chapters(chapter_id TEXT PRIMARY KEY REFERENCES editor_chapters(id) ON DELETE CASCADE,remote_status TEXT NOT NULL DEFAULT 'draft',submission_id TEXT,review_reason TEXT,remote_generation INTEGER NOT NULL DEFAULT 0,remote_sort_order INTEGER)");
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_submissions(id TEXT PRIMARY KEY,work_id TEXT NOT NULL REFERENCES editor_works(id),metadata TEXT NOT NULL,server_submission_id TEXT,state TEXT NOT NULL DEFAULT 'prepared',result TEXT,created_at INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_submission_chapters(submission_id TEXT NOT NULL REFERENCES editor_app_submissions(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,chapter_id TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,generation INTEGER NOT NULL,sha256 TEXT NOT NULL,sort_order INTEGER NOT NULL,PRIMARY KEY(submission_id,ordinal))");
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_reading(work_id TEXT PRIMARY KEY,chapter_id TEXT NOT NULL,page INTEGER NOT NULL,release_id TEXT,updated_at INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE IF NOT EXISTS editor_app_annotations(id TEXT PRIMARY KEY,work_id TEXT NOT NULL,chapter_id TEXT NOT NULL,page INTEGER NOT NULL,note TEXT,created_at INTEGER NOT NULL)");
	}

	@Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion){
		if(oldVersion<2) createAppTables(db);
		else if(oldVersion<3){
			db.execSQL("ALTER TABLE editor_app_metadata ADD COLUMN server_client_work_id TEXT");
			db.execSQL("ALTER TABLE editor_app_metadata ADD COLUMN owner_payload TEXT");
			db.execSQL("ALTER TABLE editor_app_metadata ADD COLUMN owner_conflict TEXT");
		}
	}

	public static String databaseName(String accountId){return "novel_editor_"+EditorText.sha256(Objects.requireNonNull(accountId)).substring(0,24)+".db";}

	/** Metadata/bindings are additive: existing author drafts and private Room books are untouched. */
	public void putAppMetadata(String workId,String payload,@Nullable String serverId,@Nullable String releaseId){
		assertOwnWork(workId); if(payload==null||payload.length()>32_000)throw new IllegalArgumentException("Invalid metadata");
		ContentValues v=new ContentValues();v.put("work_id",workId);v.put("payload",payload);v.put("server_work_id",serverId);v.put("base_release_id",releaseId);
		// Updating local metadata must not erase the separately persisted owner binding or conflict.
		if(getWritableDatabase().update("editor_app_metadata",v,"work_id=?",new String[]{workId})==0)insert(getWritableDatabase(),"editor_app_metadata",v);
	}
	@Nullable public AppMetadata appMetadata(String workId){assertOwnWork(workId);try(Cursor c=getReadableDatabase().rawQuery("SELECT payload,server_work_id,base_release_id,server_client_work_id,owner_payload,owner_conflict FROM editor_app_metadata WHERE work_id=?",new String[]{workId})){return c.moveToFirst()?new AppMetadata(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)):null;}}
	public void putOwnerState(String workId,String clientWorkId,@Nullable String payload,@Nullable String conflict){
		AppMetadata old=appMetadata(workId);if(old==null)throw new IllegalArgumentException("Metadata not found");
		if(clientWorkId==null||clientWorkId.isBlank())throw new IllegalArgumentException("Owner client_work_id required");
		if(old.clientWorkId()!=null&&!old.clientWorkId().equals(clientWorkId))throw new IllegalStateException("Owner client_work_id changed");
		ContentValues v=new ContentValues();v.put("server_client_work_id",clientWorkId);v.put("owner_payload",payload);v.put("owner_conflict",conflict);getWritableDatabase().update("editor_app_metadata",v,"work_id=?",new String[]{workId});
	}
	public void acknowledgeOwnerConflict(String workId,String expectedOwnerPayload){AppMetadata old=appMetadata(workId);if(old==null||!Objects.equals(old.ownerPayload(),expectedOwnerPayload))throw new IllegalStateException("Owner state changed; refresh before confirming");ContentValues v=new ContentValues();v.putNull("owner_conflict");getWritableDatabase().update("editor_app_metadata",v,"work_id=?",new String[]{workId});}
	@Nullable public String localWorkForServer(String serverId){try(Cursor c=getReadableDatabase().rawQuery("SELECT m.work_id FROM editor_app_metadata m JOIN editor_works w ON w.id=m.work_id WHERE m.server_work_id=? AND w.account_id=? AND w.deleted_at IS NULL",new String[]{serverId,accountId})){return c.moveToFirst()?c.getString(0):null;}}
	public record AppMetadata(String payload,@Nullable String serverId,@Nullable String releaseId,@Nullable String clientWorkId,@Nullable String ownerPayload,@Nullable String ownerConflict){}
	public record RemoteChapter(String status,@Nullable String submissionId,@Nullable String reason,long generation){}
	public RemoteChapter remoteChapter(String chapterId){if(getChapter(chapterId)==null)throw new IllegalArgumentException("Chapter not found");try(Cursor c=getReadableDatabase().rawQuery("SELECT remote_status,submission_id,review_reason,remote_generation FROM editor_app_chapters WHERE chapter_id=?",new String[]{chapterId})){return c.moveToFirst()?new RemoteChapter(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)):new RemoteChapter("draft",null,null,0);}}
	public void putRemoteChapter(String chapterId,String status,@Nullable String submissionId,@Nullable String reason,long generation){if(getChapter(chapterId)==null)throw new IllegalArgumentException("Chapter not found");ContentValues v=new ContentValues();v.put("chapter_id",chapterId);v.put("remote_status",status);v.put("submission_id",submissionId);v.put("review_reason",reason);v.put("remote_generation",generation);if(getWritableDatabase().update("editor_app_chapters",v,"chapter_id=?",new String[]{chapterId})==0)insert(getWritableDatabase(),"editor_app_chapters",v);}

	public void renameChapter(String chapterId,String title){Chapter c=getChapter(chapterId);if(c==null)throw new IllegalArgumentException("Chapter not found");ContentValues v=new ContentValues();v.put("title",EditorText.bounded(title,EditorLimits.CHAPTER_TITLE,"Title"));v.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("editor_chapters",v,"id=?",new String[]{chapterId});}
	public void updateWorkInfo(String workId,String title,String description){assertOwnWork(workId);if(description==null||description.length()>EditorLimits.WORK_DESCRIPTION)throw new IllegalArgumentException("Invalid description");ContentValues v=new ContentValues();v.put("title",EditorText.bounded(title,EditorLimits.WORK_TITLE,"Title"));v.put("description",description);getWritableDatabase().update("editor_works",v,"id=? AND account_id=?",new String[]{workId,accountId});touchWork(getWritableDatabase(),workId,System.currentTimeMillis());}
	public SaveResult checkpointWithTitle(String chapterId,long expected,String title,String body,int start,int end,int scroll){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{renameChapter(chapterId,title);SaveResult result=checkpoint(chapterId,expected,body,start,end,scroll,true);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}}
	/** Header-only owner mirror; never overwrite an existing local chapter. */
	public void ensureRemoteChapter(String workId,String chapterId,String title,int ordinal,long remoteGeneration,String status,@Nullable String submissionId,@Nullable String reason){
		assertOwnWork(workId);Chapter existing=getChapter(chapterId);if(existing!=null){if(!existing.workId().equals(workId))throw new IllegalArgumentException("Chapter belongs to another work");putRemoteChapter(chapterId,status,submissionId,reason,remoteGeneration);putRemoteSort(chapterId,ordinal);return;}
		SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{String volume=structure(workId).volumes().get(0).id();insertChapter(db,workId,volume,chapterId,EditorText.bounded(title,EditorLimits.CHAPTER_TITLE,"Title"),(ordinal+1L)*1024,"",System.currentTimeMillis());ContentValues v=new ContentValues();v.put("state","REMOTE_STUB");db.update("editor_chapters",v,"id=?",new String[]{chapterId});putRemoteChapter(chapterId,status,submissionId,reason,remoteGeneration);putRemoteSort(chapterId,ordinal);db.setTransactionSuccessful();}finally{db.endTransaction();}
	}
	private void putRemoteSort(String chapterId,int sort){ContentValues v=new ContentValues();v.put("remote_sort_order",sort);getWritableDatabase().update("editor_app_chapters",v,"chapter_id=?",new String[]{chapterId});}
	public void hydrateRemoteChapter(String chapterId,String body,long remoteGeneration){
		Chapter c=getChapter(chapterId);if(c==null||!"REMOTE_STUB".equals(c.state()))return;
		SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{SaveResult saved=checkpoint(chapterId,c.currentGeneration(),body,0,0,0,true);long generation=Math.max(saved.generation(),remoteGeneration+1);ContentValues v=new ContentValues();v.put("current_generation",generation);db.update("editor_chapters",v,"id=?",new String[]{chapterId});v=new ContentValues();v.put("generation",generation);db.update("editor_drafts",v,"chapter_id=?",new String[]{chapterId});db.setTransactionSuccessful();}finally{db.endTransaction();}
	}
	public void reorderChapters(String workId,List<String> ids){assertOwnWork(workId);List<Chapter> chapters=structure(workId).chapters();if(ids.size()!=chapters.size()||new java.util.HashSet<>(ids).size()!=ids.size()||!new java.util.HashSet<>(ids).equals(chapters.stream().map(Chapter::id).collect(java.util.stream.Collectors.toSet())))throw new IllegalArgumentException("Chapter list changed");SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{int i=1;for(String id:ids){ContentValues v=new ContentValues();v.put("sort_key",i++*1024L);db.update("editor_chapters",v,"id=? AND work_id=?",new String[]{id,workId});}touchWork(db,workId,System.currentTimeMillis());db.setTransactionSuccessful();}finally{db.endTransaction();}}
	public void softDeleteChapter(String chapterId){Chapter c=getChapter(chapterId);if(c==null)throw new IllegalArgumentException("Chapter not found");ContentValues v=new ContentValues();v.put("deleted_at",System.currentTimeMillis());getWritableDatabase().update("editor_chapters",v,"id=?",new String[]{chapterId});touchWork(getWritableDatabase(),c.workId(),System.currentTimeMillis());}

	/** Freeze only explicitly selected bodies, transactionally; later edits cannot alter a retry. */
	public String prepareAppSubmission(String workId,String metadata,List<String> chapterIds){
		assertOwnWork(workId);java.util.Set<String> selected=new java.util.HashSet<>(chapterIds);
		if(chapterIds.isEmpty()||chapterIds.size()>1000||selected.size()!=chapterIds.size())throw new IllegalArgumentException("Select 1–1000 unique chapters");
		if(metadata==null||metadata.length()>32_000)throw new IllegalArgumentException("Invalid metadata");
		String id=UUID.randomUUID().toString();SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
			List<Chapter> directory=structure(workId).chapters();
			if(!directory.stream().map(Chapter::id).collect(java.util.stream.Collectors.toSet()).containsAll(selected))throw new IllegalArgumentException("Chapter not found");
			java.util.Map<String,Integer> remoteSort=new java.util.HashMap<>();int nextSort=0;
			try(Cursor c=db.rawQuery("SELECT a.chapter_id,a.remote_sort_order FROM editor_app_chapters a JOIN editor_chapters c ON c.id=a.chapter_id WHERE c.work_id=? AND a.remote_sort_order IS NOT NULL",new String[]{workId})){while(c.moveToNext()){remoteSort.put(c.getString(0),c.getInt(1));nextSort=Math.max(nextSort,c.getInt(1)+1);}}
			boolean completeOrder=selected.containsAll(remoteSort.keySet());
			ContentValues v=new ContentValues();v.put("id",id);v.put("work_id",workId);v.put("metadata",metadata);v.put("created_at",System.currentTimeMillis());insert(db,"editor_app_submissions",v);
			int ordinal=0;long total=0;java.util.Set<Integer> sortOrders=new java.util.HashSet<>();
			for(int index=0;index<directory.size();index++){
				Chapter c=directory.get(index);if(!selected.contains(c.id()))continue;
				if("REMOTE_STUB".equals(c.state()))throw new IllegalArgumentException("Load selected chapters first");
				Draft d=loadDraft(c.id());if(d==null||d.content().isEmpty())throw new IllegalArgumentException("Selected chapter is empty");
				total+=d.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(total>20L*1024*1024)throw new IllegalArgumentException("Selected text exceeds 20 MiB");
				int sort=completeOrder?index:remoteSort.containsKey(c.id())?remoteSort.get(c.id()):nextSort++;
				if(sort<0||!sortOrders.add(sort))throw new IllegalArgumentException("Chapter order conflicts; refresh the owner directory");
				v=new ContentValues();v.put("submission_id",id);v.put("ordinal",ordinal++);v.put("chapter_id",c.id());v.put("title",c.title());v.put("body",d.content());v.put("generation",Math.max(c.currentGeneration(),remoteChapter(c.id()).generation()+1));v.put("sha256",d.sha256());v.put("sort_order",sort);insert(db,"editor_app_submission_chapters",v);
			}
			db.setTransactionSuccessful();return id;
		}finally{db.endTransaction();}
	}
	public record AppSubmission(String id,String workId,String metadata,@Nullable String serverSubmissionId,String state,@Nullable String result){}
	public AppSubmission appSubmission(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT s.id,s.work_id,s.metadata,s.server_submission_id,s.state,s.result FROM editor_app_submissions s JOIN editor_works w ON w.id=s.work_id WHERE s.id=? AND w.account_id=?",new String[]{id,accountId})){if(!c.moveToFirst())throw new IllegalArgumentException("Submission not found");return new AppSubmission(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5));}}
	@Nullable public String pendingAppSubmission(String workId){assertOwnWork(workId);try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM editor_app_submissions WHERE work_id=? AND state IN ('prepared','starting','uploading') ORDER BY created_at DESC,rowid DESC LIMIT 1",new String[]{workId})){return c.moveToFirst()?c.getString(0):null;}}
	@Nullable public String localAppSubmissionForServer(String workId,String remoteId){assertOwnWork(workId);try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM editor_app_submissions WHERE work_id=? AND server_submission_id=? LIMIT 1",new String[]{workId,remoteId})){return c.moveToFirst()?c.getString(0):null;}}
	@Nullable public String cancellableAppSubmission(String workId){assertOwnWork(workId);try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM editor_app_submissions WHERE work_id=? AND state IN ('prepared','starting','uploading','pending') ORDER BY created_at DESC,rowid DESC LIMIT 1",new String[]{workId})){return c.moveToFirst()?c.getString(0):null;}}
	public List<String> appSubmissionChapterIds(String id){appSubmission(id);ArrayList<String> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT chapter_id FROM editor_app_submission_chapters WHERE submission_id=? ORDER BY ordinal",new String[]{id})){while(c.moveToNext())result.add(c.getString(0));}return result;}
	public record SubmissionChapter(String id,String title,String body,long generation,String sha256,int sortOrder){}
	public SubmissionChapter appSubmissionChapter(String id,int ordinal){appSubmission(id);try(Cursor c=getReadableDatabase().rawQuery("SELECT chapter_id,title,body,generation,sha256,sort_order FROM editor_app_submission_chapters WHERE submission_id=? AND ordinal=?",new String[]{id,String.valueOf(ordinal)})){if(!c.moveToFirst())throw new IllegalArgumentException("Chapter not found");return new SubmissionChapter(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5));}}
	public void updateAppSubmission(String id,@Nullable String remoteId,String state,@Nullable String result){appSubmission(id);ContentValues v=new ContentValues();v.put("server_submission_id",remoteId);v.put("state",state);v.put("result",result);getWritableDatabase().update("editor_app_submissions",v,"id=?",new String[]{id});}

	public CreatedWork appendReviewedImport(String jobId,String workId){
		assertOwnWork(workId);ImportModels.Job job=getImportJob(jobId);if(job==null||!"AWAITING_REVIEW".equals(job.state()))throw new IllegalArgumentException("Import is not ready");
		SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{String volume=structure(workId).volumes().get(0).id(),first=null;long sort;try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(sort_key),0)+1024 FROM editor_chapters WHERE work_id=?",new String[]{workId})){c.moveToFirst();sort=c.getLong(0);}try(Cursor c=db.rawQuery("SELECT suggested_title,content FROM editor_import_chapters WHERE job_id=? AND included=1 ORDER BY ordinal",new String[]{jobId})){while(c.moveToNext()){String id=UUID.randomUUID().toString();if(first==null)first=id;insertChapter(db,workId,volume,id,EditorText.bounded(c.getString(0),EditorLimits.CHAPTER_TITLE,"Title"),sort,c.getString(1),System.currentTimeMillis());sort+=1024;}}if(first==null)throw new IllegalArgumentException("Select chapters");ContentValues done=new ContentValues();done.put("state","READY");done.put("phase","COMMITTED");done.put("result_work_id",workId);if(db.update("editor_import_jobs",done,"id=? AND state='AWAITING_REVIEW'",new String[]{jobId})!=1)throw new IllegalStateException("Import changed");touchWork(db,workId,System.currentTimeMillis());db.setTransactionSuccessful();return new CreatedWork(workId,volume,first);}finally{db.endTransaction();}
	}
	public record ReadingMark(String chapterId,int page,@Nullable String releaseId,long updatedAt){}
	@Nullable public ReadingMark readingMark(String workId){try(Cursor c=getReadableDatabase().rawQuery("SELECT chapter_id,page,release_id,updated_at FROM editor_app_reading WHERE work_id=?",new String[]{workId})){return c.moveToFirst()?new ReadingMark(c.getString(0),c.getInt(1),c.getString(2),c.getLong(3)):null;}}
	public void putReadingMark(String workId,String chapterId,int page,@Nullable String releaseId){ContentValues v=new ContentValues();v.put("work_id",workId);v.put("chapter_id",chapterId);v.put("page",Math.max(0,page));v.put("release_id",releaseId);v.put("updated_at",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("editor_app_reading",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
	public void addReadingAnnotation(String workId,String chapterId,int page,@Nullable String note){ContentValues v=new ContentValues();v.put("id",UUID.randomUUID().toString());v.put("work_id",workId);v.put("chapter_id",chapterId);v.put("page",Math.max(0,page));v.put("note",note);v.put("created_at",System.currentTimeMillis());insert(getWritableDatabase(),"editor_app_annotations",v);}
	public record ReadingAnnotation(String id,String chapterId,int page,@Nullable String note){}
	public List<ReadingAnnotation> readingAnnotations(String workId){ArrayList<ReadingAnnotation> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,chapter_id,page,note FROM editor_app_annotations WHERE work_id=? ORDER BY created_at",new String[]{workId})){while(c.moveToNext())result.add(new ReadingAnnotation(c.getString(0),c.getString(1),c.getInt(2),c.getString(3)));}return result;}


	public ImportModels.Job createImportJob(String displayName,String mimeType,String stagedPath,long sourceSize,String sourceSha256){
		if(sourceSize<=0 || sourceSize>ImportLimits.SOURCE_BYTES) throw new IllegalArgumentException("Source size out of range");
		String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
		ContentValues cv=new ContentValues(); cv.put("id",id); cv.put("account_id",accountId); cv.put("display_name",displayName); cv.put("mime_type",mimeType); cv.put("staged_path",stagedPath); cv.put("source_size",sourceSize); cv.put("source_sha256",sourceSha256); cv.put("phase","STAGED"); cv.put("state","QUEUED"); cv.put("created_at",now); cv.put("updated_at",now);
		insert(getWritableDatabase(),"editor_import_jobs",cv); return getImportJob(id);
	}

	public void storeImportResult(String jobId,ImportModels.Result result){
		Objects.requireNonNull(result); if(result.chapters().isEmpty() || result.chapters().size()>ImportLimits.CHAPTERS) throw new IllegalArgumentException("Invalid import result");
		SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
		try{
			db.delete("editor_import_chapters","job_id=?",new String[]{jobId}); int total=0;
			for(ImportModels.Chapter chapter:result.chapters()){
				String content=EditorText.normalize(chapter.content()); total+=content.length(); if(total>ImportLimits.TOTAL_TEXT) throw new IllegalArgumentException("Import text too large");
				ContentValues cv=new ContentValues(); cv.put("job_id",jobId); cv.put("ordinal",chapter.ordinal()); cv.put("suggested_title",chapter.title()); cv.put("content",content); cv.put("source_anchor",chapter.sourceAnchor()); cv.put("content_length",content.length()); cv.put("sha256",EditorText.sha256(content)); insert(db,"editor_import_chapters",cv);
			}
			ContentValues job=new ContentValues(); job.put("format",result.metadata().format()); job.put("encoding",result.metadata().encoding()); job.put("phase","REVIEW"); job.put("state","AWAITING_REVIEW"); job.put("items_done",result.chapters().size()); job.put("items_total",result.chapters().size()); job.put("warning_count",result.warnings().size()); job.put("updated_at",System.currentTimeMillis());
			if(db.update("editor_import_jobs",job,"id=? AND account_id=?",new String[]{jobId,accountId})!=1) throw new IllegalArgumentException("Import job not found");
			db.setTransactionSuccessful();
		}finally{ db.endTransaction(); }
	}

	public CreatedWork commitImport(String jobId,String title,String description,String category){
		ImportModels.Job job=getImportJob(jobId); if(job==null || !"AWAITING_REVIEW".equals(job.state())) throw new IllegalArgumentException("Import is not ready");
		String cleanTitle=EditorText.bounded(title,EditorLimits.WORK_TITLE,"Title"), cleanDescription=EditorText.normalize(description);
		if(cleanDescription.length()>EditorLimits.WORK_DESCRIPTION) throw new IllegalArgumentException("Description too long");
		long now=System.currentTimeMillis(); String work=UUID.randomUUID().toString(), volume=UUID.randomUUID().toString(); String first=null;
		SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
		try{
			ContentValues w=new ContentValues(); w.put("id",work); w.put("account_id",accountId); w.put("title",cleanTitle); w.put("description",cleanDescription); w.put("category",category==null||category.isBlank()?"fiction":category); w.put("source_format",job.format()); w.put("created_at",now); w.put("updated_at",now); w.put("last_opened_at",now); insert(db,"editor_works",w);
			ContentValues v=new ContentValues(); v.put("id",volume); v.put("work_id",work); v.put("title","正文"); v.put("sort_key",1024); v.put("created_at",now); v.put("updated_at",now); insert(db,"editor_volumes",v);
			try(Cursor c=db.rawQuery("SELECT suggested_title,content,ordinal FROM editor_import_chapters WHERE job_id=? AND included=1 ORDER BY ordinal",new String[]{jobId})){
				while(c.moveToNext()){ String id=UUID.randomUUID().toString(); if(first==null) first=id; insertChapter(db,work,volume,id,EditorText.bounded(c.getString(0),EditorLimits.CHAPTER_TITLE,"Chapter title"),(c.getInt(2)+1L)*1024,c.getString(1),now); }
			}
			if(first==null) throw new IllegalArgumentException("No chapters selected");
			ContentValues done=new ContentValues(); done.put("state","READY"); done.put("phase","COMMITTED"); done.put("result_work_id",work); done.put("updated_at",now); if(db.update("editor_import_jobs",done,"id=? AND account_id=? AND state='AWAITING_REVIEW'",new String[]{jobId,accountId})!=1) throw new IllegalStateException("Import job changed");
			db.setTransactionSuccessful(); return new CreatedWork(work,volume,first);
		}finally{ db.endTransaction(); }
	}

	@Nullable public ImportModels.Job getImportJob(String id){
		try(Cursor c=getReadableDatabase().rawQuery("SELECT id,account_id,display_name,mime_type,format,staged_path,source_size,source_sha256,phase,state,bytes_done,bytes_total,items_done,items_total,encoding,error_code,error_detail,warning_count,result_work_id,created_at,updated_at FROM editor_import_jobs WHERE id=? AND account_id=?",new String[]{id,accountId})){
			return c.moveToFirst()?new ImportModels.Job(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7),c.getString(8),c.getString(9),c.getLong(10),c.getLong(11),c.getInt(12),c.getInt(13),c.getString(14),c.getString(15),c.getString(16),c.getInt(17),c.getString(18),c.getLong(19),c.getLong(20)):null;
		}
	}

	public List<ImportModels.Chapter> importChapters(String jobId){
		ArrayList<ImportModels.Chapter> result=new ArrayList<>();
		try(Cursor c=getReadableDatabase().rawQuery("SELECT ordinal,suggested_title,content,source_anchor FROM editor_import_chapters WHERE job_id=? ORDER BY ordinal",new String[]{jobId})){ while(c.moveToNext()) result.add(new ImportModels.Chapter(c.getInt(0),c.getString(1),c.getString(2),c.getString(3))); }
		return result;
	}

	public String getOrCreatePublishOperation(String workId,long snapshotVersion,String requestHash){
		assertOwnWork(workId);
		try(Cursor c=getReadableDatabase().rawQuery("SELECT operation_id,request_hash FROM editor_publish_jobs WHERE work_id=? AND snapshot_version=? AND state!='COMPLETED' ORDER BY created_at DESC LIMIT 1",new String[]{workId,String.valueOf(snapshotVersion)})){
			if(c.moveToFirst()){
				if(!Objects.equals(requestHash,c.getString(1)))throw new IllegalStateException("Publish snapshot changed");
				return c.getString(0);
			}
		}
		String id=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();long now=System.currentTimeMillis();
		ContentValues cv=new ContentValues();cv.put("id",id);cv.put("work_id",workId);cv.put("snapshot_version",snapshotVersion);cv.put("state","PENDING");cv.put("operation_id",operation);cv.put("request_hash",requestHash);cv.put("created_at",now);cv.put("updated_at",now);insert(getWritableDatabase(),"editor_publish_jobs",cv);return operation;
	}
	public void markPublishCompleted(String operationId){ContentValues cv=new ContentValues();cv.put("state","COMPLETED");cv.putNull("last_error");cv.put("updated_at",System.currentTimeMillis());if(getWritableDatabase().update("editor_publish_jobs",cv,"operation_id=?",new String[]{operationId})!=1)throw new IllegalStateException("Publish operation not found");}
	public void markPublishFailed(String operationId,String error){ContentValues cv=new ContentValues();cv.put("state","FAILED");cv.put("last_error",error==null?"unknown":error.substring(0,Math.min(500,error.length())));cv.put("updated_at",System.currentTimeMillis());getWritableDatabase().update("editor_publish_jobs",cv,"operation_id=?",new String[]{operationId});}

	public CreatedWork createWork(String title, String description, String category){
		String cleanTitle=EditorText.bounded(title, EditorLimits.WORK_TITLE, "Title");
		String cleanDescription=EditorText.normalize(description);
		if(cleanDescription.length()>EditorLimits.WORK_DESCRIPTION) throw new IllegalArgumentException("Description too long");
		String cleanCategory=category==null || category.isBlank() ? "fiction" : category;
		long now=System.currentTimeMillis();
		String work=UUID.randomUUID().toString(), volume=UUID.randomUUID().toString(), chapter=UUID.randomUUID().toString();
		SQLiteDatabase db=getWritableDatabase();
		db.beginTransaction();
		try{
			ContentValues w=new ContentValues(); w.put("id", work); w.put("account_id", accountId); w.put("title", cleanTitle); w.put("description", cleanDescription); w.put("category", cleanCategory); w.put("created_at", now); w.put("updated_at", now); w.put("last_opened_at", now);
			insert(db, "editor_works", w);
			ContentValues v=new ContentValues(); v.put("id", volume); v.put("work_id", work); v.put("title", "正文"); v.put("sort_key", 1024); v.put("created_at", now); v.put("updated_at", now); insert(db, "editor_volumes", v);
			insertChapter(db, work, volume, chapter, "第一章", 1024, "", now);
			db.setTransactionSuccessful();
			return new CreatedWork(work, volume, chapter);
		}finally{ db.endTransaction(); }
	}

	public Chapter addChapter(String workId, String volumeId, String title){
		assertOwnWork(workId);
		String clean=EditorText.bounded(title, EditorLimits.CHAPTER_TITLE, "Chapter title");
		long now=System.currentTimeMillis();
		SQLiteDatabase db=getWritableDatabase();
		long sort=1024;
		try(Cursor c=db.rawQuery("SELECT COALESCE(MAX(sort_key),0)+1024 FROM editor_chapters WHERE work_id=? AND volume_id=? AND deleted_at IS NULL", new String[]{workId, volumeId})){ if(c.moveToFirst()) sort=c.getLong(0); }
		String id=UUID.randomUUID().toString();
		db.beginTransaction();
		try{ insertChapter(db, workId, volumeId, id, clean, sort, "", now); touchWork(db, workId, now); db.setTransactionSuccessful(); }
		finally{ db.endTransaction(); }
		return getChapter(id);
	}

	public SaveResult checkpoint(String chapterId, String content, int selectionStart, int selectionEnd, int scrollY, boolean dirty){
		Chapter chapter=getChapter(chapterId);
		if(chapter==null) throw new IllegalArgumentException("Chapter not found");
		return checkpoint(chapterId, chapter.currentGeneration(), content, selectionStart, selectionEnd, scrollY, dirty);
	}

	public SaveResult checkpoint(String chapterId, long expectedGeneration, String content, int selectionStart, int selectionEnd, int scrollY, boolean dirty){
		String normalized=EditorText.normalize(content);
		if(normalized.length()>EditorLimits.CHAPTER_CONTENT) throw new IllegalArgumentException("Chapter content too long");
		Chapter chapter=getChapter(chapterId);
		if(chapter==null || chapter.deletedAt()!=null) throw new IllegalArgumentException("Chapter not found");
		if(chapter.currentGeneration()!=expectedGeneration) throw new IllegalStateException("Chapter changed concurrently");
		long generation=expectedGeneration+1, now=System.currentTimeMillis();
		String hash=EditorText.sha256(normalized);
		SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
		try{
			ContentValues draft=new ContentValues(); draft.put("chapter_id", chapterId); draft.put("content", normalized); draft.put("generation", generation); draft.put("sha256", hash); draft.put("dirty", dirty ? 1 : 0); draft.put("selection_start", clamp(selectionStart, normalized.length())); draft.put("selection_end", clamp(selectionEnd, normalized.length())); draft.put("scroll_y", Math.max(0, scrollY)); draft.put("saved_at", now);
			db.insertWithOnConflict("editor_drafts", null, draft, SQLiteDatabase.CONFLICT_REPLACE);
			ContentValues cv=new ContentValues(); cv.put("current_generation", generation); cv.put("content_length", normalized.length()); cv.put("content_sha256", hash); cv.put("updated_at", now); cv.put("state", dirty ? "LOCAL_DRAFT" : "SYNCED");
			if(db.update("editor_chapters", cv, "id=? AND current_generation=?", new String[]{chapterId, String.valueOf(chapter.currentGeneration())})!=1) throw new IllegalStateException("Chapter changed concurrently");
			db.delete("editor_edit_journal", "chapter_id=?", new String[]{chapterId}); touchWork(db, chapter.workId(), now);
			db.setTransactionSuccessful(); return new SaveResult(generation, hash, normalized.length(), now);
		}finally{ db.endTransaction(); }
	}

	public long appendJournal(String chapterId, long baseGeneration, int start, int deletedCount, String inserted){
		if(inserted==null || inserted.length()>EditorLimits.JOURNAL_INSERT || start<0 || deletedCount<0) throw new IllegalArgumentException("Invalid edit");
		SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
		try{
			Chapter chapter=getChapter(chapterId);
			if(chapter==null || chapter.currentGeneration()!=baseGeneration) throw new IllegalStateException("Journal base generation changed");
			db.delete("editor_edit_journal","chapter_id=?",new String[]{chapterId});
			ContentValues cv=new ContentValues(); cv.put("chapter_id", chapterId); cv.put("seq", 1); cv.put("base_generation", baseGeneration); cv.put("start_offset", start); cv.put("deleted_count", deletedCount); cv.put("inserted_text", inserted); cv.put("created_at", System.currentTimeMillis());
			insert(db,"editor_edit_journal",cv); db.setTransactionSuccessful(); return 1;
		}finally{ db.endTransaction(); }
	}

	public Draft loadDraft(String chapterId){
		try(Cursor c=getReadableDatabase().rawQuery("SELECT content,generation,sha256,dirty,selection_start,selection_end,scroll_y,saved_at FROM editor_drafts WHERE chapter_id=?", new String[]{chapterId})){
			if(!c.moveToFirst()) return null;
			String content=c.getString(0); long generation=c.getLong(1);
			try(Cursor journal=getReadableDatabase().rawQuery("SELECT base_generation,start_offset,deleted_count,inserted_text FROM editor_edit_journal WHERE chapter_id=? ORDER BY seq", new String[]{chapterId})){
				while(journal.moveToNext()){
					if(journal.getLong(0)!=generation) throw new IllegalStateException("Journal base generation mismatch");
					content=EditorJournal.apply(content, journal.getInt(1), journal.getInt(2), journal.getString(3));
				}
			}
			return new Draft(chapterId, content, generation, EditorText.sha256(content), c.getInt(3)!=0, c.getInt(4), c.getInt(5), c.getInt(6), c.getLong(7));
		}
	}

	public Snapshot snapshot(String chapterId, String reason){
		Draft draft=loadDraft(chapterId); if(draft==null) throw new IllegalArgumentException("Draft not found");
		String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
		ContentValues cv=new ContentValues(); cv.put("id", id); cv.put("chapter_id", chapterId); cv.put("generation", draft.generation()); cv.put("content", draft.content()); cv.put("sha256", draft.sha256()); cv.put("reason", Objects.requireNonNull(reason)); cv.put("created_at", now);
		SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
		try{
			insert(db, "editor_snapshots", cv);
			db.execSQL("DELETE FROM editor_snapshots WHERE chapter_id=? AND id NOT IN (SELECT id FROM editor_snapshots WHERE chapter_id=? ORDER BY created_at DESC,id DESC LIMIT ?)", new Object[]{chapterId, chapterId, EditorLimits.MAX_SNAPSHOTS_PER_CHAPTER});
			db.setTransactionSuccessful();
		}finally{ db.endTransaction(); }
		return new Snapshot(id, chapterId, draft.generation(), draft.content(), draft.sha256(), reason, now);
	}

	public void softDeleteWork(String workId){
		assertOwnWork(workId); long now=System.currentTimeMillis();
		ContentValues cv=new ContentValues(); cv.put("deleted_at", now); cv.put("updated_at", now);
		getWritableDatabase().update("editor_works", cv, "id=? AND account_id=?", new String[]{workId, accountId});
	}
	public void restoreWork(String workId){
		ContentValues cv=new ContentValues(); cv.putNull("deleted_at"); cv.put("updated_at", System.currentTimeMillis());
		if(getWritableDatabase().update("editor_works", cv, "id=? AND account_id=?", new String[]{workId, accountId})!=1) throw new IllegalArgumentException("Work not found");
	}
	public void permanentlyDeleteWork(String workId){
		if(getWritableDatabase().delete("editor_works", "id=? AND account_id=? AND deleted_at IS NOT NULL", new String[]{workId, accountId})!=1) throw new IllegalArgumentException("Trashed work not found");
	}

	public List<Work> listWorks(boolean trashed){
		ArrayList<Work> result=new ArrayList<>();
		String deleted=trashed ? "IS NOT NULL" : "IS NULL";
		try(Cursor c=getReadableDatabase().rawQuery("SELECT w.id,w.account_id,w.title,w.description,w.category,w.declared_rating,w.content_warning,w.state,w.source_format,w.local_version,w.created_at,w.updated_at,w.last_opened_at,w.deleted_at,COUNT(c.id),COALESCE(SUM(c.content_length),0) FROM editor_works w LEFT JOIN editor_chapters c ON c.work_id=w.id AND c.deleted_at IS NULL WHERE w.account_id=? AND w.deleted_at "+deleted+" GROUP BY w.id ORDER BY w.updated_at DESC,w.id", new String[]{accountId})){
			while(c.moveToNext()) result.add(work(c));
		}
		return result;
	}

	public WorkStructure structure(String workId){
		Work work=getWork(workId); if(work==null) throw new IllegalArgumentException("Work not found");
		ArrayList<Volume> volumes=new ArrayList<>();
		try(Cursor c=getReadableDatabase().rawQuery("SELECT id,work_id,title,sort_key,created_at,updated_at,deleted_at FROM editor_volumes WHERE work_id=? AND deleted_at IS NULL ORDER BY sort_key,id", new String[]{workId})){ while(c.moveToNext()) volumes.add(new Volume(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5),nullableLong(c,6))); }
		ArrayList<Chapter> chapters=new ArrayList<>();
		try(Cursor c=getReadableDatabase().rawQuery("SELECT id,work_id,volume_id,title,sort_key,current_generation,content_length,content_sha256,state,created_at,updated_at,deleted_at FROM editor_chapters WHERE work_id=? AND deleted_at IS NULL ORDER BY volume_id,sort_key,id", new String[]{workId})){ while(c.moveToNext()) chapters.add(chapter(c)); }
		return new WorkStructure(work, volumes, chapters);
	}

	@Nullable public Work getWork(String id){
		try(Cursor c=getReadableDatabase().rawQuery("SELECT w.id,w.account_id,w.title,w.description,w.category,w.declared_rating,w.content_warning,w.state,w.source_format,w.local_version,w.created_at,w.updated_at,w.last_opened_at,w.deleted_at,COUNT(c.id),COALESCE(SUM(c.content_length),0) FROM editor_works w LEFT JOIN editor_chapters c ON c.work_id=w.id AND c.deleted_at IS NULL WHERE w.id=? AND w.account_id=? GROUP BY w.id", new String[]{id, accountId})){ return c.moveToFirst() ? work(c) : null; }
	}
	@Nullable public Chapter getChapter(String id){
		try(Cursor c=getReadableDatabase().rawQuery("SELECT c.id,c.work_id,c.volume_id,c.title,c.sort_key,c.current_generation,c.content_length,c.content_sha256,c.state,c.created_at,c.updated_at,c.deleted_at FROM editor_chapters c JOIN editor_works w ON w.id=c.work_id WHERE c.id=? AND w.account_id=?", new String[]{id, accountId})){ return c.moveToFirst() ? chapter(c) : null; }
	}

	private void assertOwnWork(String id){ if(getWork(id)==null) throw new IllegalArgumentException("Work not found"); }
	private void insertChapter(SQLiteDatabase db,String work,String volume,String id,String title,long sort,String content,long now){
		String hash=EditorText.sha256(content);
		ContentValues c=new ContentValues(); c.put("id",id); c.put("work_id",work); c.put("volume_id",volume); c.put("title",title); c.put("sort_key",sort); c.put("current_generation",1); c.put("content_length",content.length()); c.put("content_sha256",hash); c.put("created_at",now); c.put("updated_at",now); insert(db,"editor_chapters",c);
		ContentValues d=new ContentValues(); d.put("chapter_id",id); d.put("content",content); d.put("generation",1); d.put("sha256",hash); d.put("dirty",1); d.put("saved_at",now); insert(db,"editor_drafts",d);
	}
	private void touchWork(SQLiteDatabase db,String id,long now){ db.execSQL("UPDATE editor_works SET updated_at=?,local_version=local_version+1 WHERE id=? AND account_id=?",new Object[]{now,id,accountId}); }
	private static void insert(SQLiteDatabase db,String table,ContentValues values){ if(db.insertOrThrow(table,null,values)<0) throw new IllegalStateException("Unable to insert "+table); }
	private static int clamp(int value,int length){ return Math.max(0,Math.min(value,length)); }
	private static Long nullableLong(Cursor c,int index){ return c.isNull(index)?null:c.getLong(index); }
	private static Work work(Cursor c){ return new Work(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8),c.getLong(9),c.getInt(14),c.getLong(15),c.getLong(10),c.getLong(11),c.getLong(12),nullableLong(c,13)); }
	private static Chapter chapter(Cursor c){ return new Chapter(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getInt(6),c.getString(7),c.getString(8),c.getLong(9),c.getLong(10),nullableLong(c,11)); }
}
