package org.joinmastodon.android.novel.editor;

import android.content.Context;

import org.joinmastodon.android.api.novels.NovelV2Api;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.noveleditor.EditorLimits;
import org.joinmastodon.noveleditor.EditorModels;
import org.joinmastodon.noveleditor.EditorText;
import org.joinmastodon.noveleditor.NovelEditorStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import androidx.annotation.Nullable;

/** Publishes an immutable local snapshot through the atomic authoring v2 release protocol. */
public final class NovelPublishClient{
	public record Progress(String phase, int itemsDone, int itemsTotal){}
	public record Result(String workId, String releaseId, int releaseVersion, int manifestVersion){}

	private final Context context; private final String accountID;
	public NovelPublishClient(Context context, String accountID){ this.context=context.getApplicationContext(); this.accountID=accountID; }

	@FunctionalInterface public interface Listener{ void onProgress(Progress progress); }

	public Result publish(NovelEditorStorage storage, String workId, @Nullable Listener listener) throws Exception{
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null) throw new IllegalStateException("Session lost");
		NovelV2Api api=new NovelV2Api(session);
		EditorModels.WorkStructure structure=storage.structure(workId);
		EditorModels.Work work=structure.work();
		if(structure.chapters().isEmpty()) throw new IllegalArgumentException("empty");
		List<PendingChapter> pending=new ArrayList<>();
		for(EditorModels.Chapter chapter : structure.chapters()){
			EditorModels.Draft draft=storage.loadDraft(chapter.id());
			if(draft==null) throw new IllegalStateException("draft");
			if(draft.generation()!=chapter.currentGeneration() || !EditorText.sha256(draft.content()).equals(chapter.contentSha256())) throw new IllegalStateException("stale:"+chapter.title());
			if(draft.content().length()>EditorLimits.CHAPTER_CONTENT) throw new IllegalStateException("limit:"+chapter.title());
			pending.add(new PendingChapter(chapter.id(), chapter.volumeId(), chapter.title(), chapter.sortKey(), chapter.currentGeneration(), draft.content()));
		}
		recordStaging(storage, pending);
		String snapshotHash=snapshotHash(work,pending);
		if(listener!=null) listener.onProgress(new Progress("start",0,pending.size()));
		NovelV2Api.SyncStartResponse start=api.start(new NovelV2Api.SyncStartRequest(workId,work.title(),work.description(),work.category(),work.declaredRating(),work.contentWarning()),operation(snapshotHash,"start"));
		List<NovelV2Api.VolumeInput> volumes=new ArrayList<>();
		for(EditorModels.Volume volume : structure.volumes()) volumes.add(new NovelV2Api.VolumeInput(volume.id(), volume.title(), volume.sortKey()));
		int part=0;
		for(int offset=0; offset<pending.size(); offset+=40){
			List<NovelV2Api.ChapterInput> chapters=new ArrayList<>();
			for(PendingChapter item : pending.subList(offset, Math.min(pending.size(), offset+40))){
				chapters.add(new NovelV2Api.ChapterInput(item.chapterId(), item.volumeId(), item.title(), item.sortKey(), item.generation(), item.body(), EditorText.sha256(item.body())));
			}
			int partIndex=part++;
			api.putPart(start.sync_id,String.valueOf(partIndex),new NovelV2Api.PartRequest(workId,volumes,chapters),operation(snapshotHash,"part:"+partIndex));
			if(listener!=null) listener.onProgress(new Progress("parts", Math.min(pending.size(), offset+chapters.size()), pending.size()));
		}
		NovelV2Api.FinalizeResponse finalized=api.finalizeSync(start.sync_id,workId,operation(snapshotHash,"finalize"));
		if(listener!=null) listener.onProgress(new Progress("release", pending.size(), pending.size()));
		String releaseFingerprint=EditorText.sha256(workId+"\n"+finalized.manifest_version);
		String releaseOperation=storage.getOrCreatePublishOperation(workId,finalized.manifest_version,releaseFingerprint);
		try{
			NovelV2Api.ReleaseResponse released=api.publishRelease(workId,finalized.manifest_version,releaseOperation);
			storage.markPublishCompleted(releaseOperation);
			return new Result(released.work_id,released.release_id,released.release_version,finalized.manifest_version);
		}catch(Exception error){
			storage.markPublishFailed(releaseOperation,error.getMessage());
			throw error;
		}
	}

	private static String snapshotHash(EditorModels.Work work,List<PendingChapter> pending){
		StringBuilder value=new StringBuilder(work.id()).append('\n').append(work.localVersion()).append('\n');
		for(PendingChapter chapter:pending)value.append(chapter.chapterId()).append(':').append(chapter.generation()).append(':').append(EditorText.sha256(chapter.body())).append('\n');
		return EditorText.sha256(value.toString());
	}
	private static String operation(String snapshot,String phase){ return UUID.nameUUIDFromBytes((snapshot+"\n"+phase).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
	private void recordStaging(NovelEditorStorage storage, List<PendingChapter> pending){
		// Barrier: every chapter body is already durable in editor_drafts; publish reads committed rows only.
		for(PendingChapter item : pending) storage.snapshot(item.chapterId(), "BEFORE_PUBLISH");
	}
	private record PendingChapter(String chapterId, String volumeId, String title, long sortKey, long generation, String body){}
}
