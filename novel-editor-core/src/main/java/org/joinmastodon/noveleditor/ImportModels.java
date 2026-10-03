package org.joinmastodon.noveleditor;

import androidx.annotation.Nullable;

import java.util.List;

public final class ImportModels{
	private ImportModels(){}

	public record Metadata(String title, @Nullable String author, @Nullable String language, String format,
			@Nullable String encoding, int encodingConfidence){}
	public record Warning(String code, String message, @Nullable String location){}
	public record Chapter(int ordinal, String title, String content, @Nullable String sourceAnchor){}
	public record Result(Metadata metadata, List<Chapter> chapters, List<Warning> warnings){}
	public record Job(String id,String accountId,String displayName,String mimeType,String format,String stagedPath,
			long sourceSize,String sourceSha256,String phase,String state,long bytesDone,long bytesTotal,
			int itemsDone,int itemsTotal,String encoding,String errorCode,String errorDetail,int warningCount,
			String resultWorkId,long createdAt,long updatedAt){}

	public interface Cancellation{
		Cancellation NONE=()->false;
		boolean isCancelled();
		default void throwIfCancelled(){ if(isCancelled()) throw new ImportException("CANCELED", "Import canceled"); }
	}
}
