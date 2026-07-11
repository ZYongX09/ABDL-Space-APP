package org.joinmastodon.android.model;

import org.parceler.Parcel;

@Parcel
public class FriendRequestReport extends BaseModel {
	public String id;
	public String request_id;
	public String reporter_id;
	public String reason;
	public String[] evidence_urls;
	public String status;
	public String admin_reply;
	public String created_at;
	public String resolved_at;
	public FriendRequestUser reporter;
	public FriendRequest request;
}
