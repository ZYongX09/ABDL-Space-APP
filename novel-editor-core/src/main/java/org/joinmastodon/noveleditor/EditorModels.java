package org.joinmastodon.noveleditor;

import androidx.annotation.Nullable;

import java.util.List;

/** Lightweight directory rows; chapter content is loaded only by chapter id. */
public final class EditorModels{
	private EditorModels(){}

	public record Work(String id, String accountId, String title, String description, String category,
			String declaredRating, String contentWarning, String state, String sourceFormat,
			long localVersion, int chapterCount, long characterCount, long createdAt, long updatedAt,
			long lastOpenedAt, @Nullable Long deletedAt){}

	public record Volume(String id, String workId, String title, long sortKey, long createdAt, long updatedAt,
			@Nullable Long deletedAt){}

	public record Chapter(String id, String workId, String volumeId, String title, long sortKey,
			long currentGeneration, int contentLength, String contentSha256, String state,
			long createdAt, long updatedAt, @Nullable Long deletedAt){}

	public record Draft(String chapterId, String content, long generation, String sha256, boolean dirty,
			int selectionStart, int selectionEnd, int scrollY, long savedAt){}

	public record Snapshot(String id, String chapterId, long generation, String content, String sha256,
			String reason, long createdAt){}

	public record WorkStructure(Work work, List<Volume> volumes, List<Chapter> chapters){}

	public record CreatedWork(String workId, String volumeId, String chapterId){}

	public record SaveResult(long generation, String sha256, int contentLength, long savedAt){}
}
