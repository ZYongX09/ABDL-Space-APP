package org.joinmastodon.android.model.albums;

import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.parceler.Parcel;

import java.util.List;
import java.util.Map;

/** Album API DTOs. Gson's LOWER_CASE_WITH_UNDERSCORES policy maps the public fields. */
public final class AlbumModels{
	private AlbumModels(){}

	public static class Album{
		public String id, name, visibility, coverUrl;
		public long ownerId, createdAt;
		public int photoCount, memberCount;
		public boolean isDefault, canUpload, isOwner, downloadProtected;
	}
	public static class Photo{
		public String id, albumId, batchId, description, previewUrl, hdUrl;
		public Long capturedAt;
		public long uploadedAt, sortAt;
		public int width, height, likesCount, commentsCount;
		public boolean originalAvailable, liked, isOwner, ownerSponsor, downloadProtected;
		/** Null for legacy servers; never interpret a missing permission as an explicit grant. */
		public Boolean canDownload;
	}
	public static class StorageQuota{
		public long limitBytes, usedBytes, reservedBytes, remainingBytes;
		public String tier;
		public boolean sponsorActive, originalUploadAllowed;
	}
	public static class Comment{
		public String id, username, displayName, avatar, content;
		public long userId, createdAt;
	}
	public static class ListResponse{
		public List<Album> albums;
		public boolean hasMore;
	}
	public static class DetailResponse{
		public Album album;
	}
	public static class PhotoResponse{
		public List<Photo> photos;
		public boolean hasMore;
	}
	public static class PhotoDetailResponse{
		public Photo photo;
	}
	public static class CommentsResponse{
		public List<Comment> comments;
		public boolean hasMore;
	}
	public static class CommentResponse{
		public Comment comment;
	}
	public static class Authorization{
		public String url;
		public long expiresAt;
		public boolean charged;
		public SponsorModels.Quota quota;
	}
	public static class UploadAuthorization{
		public String photoId, clientId, kind, uploadId, uploadUrl;
		public Map<String, String> requiredHeaders;
		public long expiresAt;
	}
	public static class BatchAuthorization{
		public String batchId, albumId;
		public List<UploadAuthorization> uploads;
		public boolean published;
		public Long postId;
	}
	public static class CancelResponse{
		public boolean cancelled;
	}
	public static class CompleteResponse{
		public boolean complete;
	}
	public static class PublishResponse{
		public String albumId, batchId;
		public Long postId;
	}
	public static class ImportResponse{
		public int imported, skipped;
		public boolean remaining;
	}
	public static class Invite{
		public String url, token;
		public long expiresAt;
	}
	public static class Member{
		public long userId;
		public String username;
	}
	public static class MembersResponse{
		public List<Member> members;
	}
	public static class LikeResponse{
		public boolean liked;
		public int likesCount;
	}
	public static class DeleteResponse{
		public boolean deleted;
	}
	/** A report as returned by POST /albums/:id/report; snake_case fields via Gson policy. */
	public static class Report{
		public String id, albumId, reason, detail, status;
		public long createdAt;
	}
	public static class ReportResponse{
		public Report report;
	}

	/** Only this payload is parcelled as part of a native Mastodon Status. */
	@Parcel
	public static class AlbumUpdate{
		public String albumId, albumName, description, coverUrl;
		public int photoCount, width, height;
		public boolean downloadProtected;
	}
}
