package org.joinmastodon.android.model;

import org.parceler.Parcel;

import java.util.List;

@Parcel
public class FriendRequestComment extends BaseModel {
	public String id;
	public String user_id;
	public String parent_id;
	public String content;
	public String created_at;
	public FriendRequestUser user;
	public List<FriendRequestComment> replies;
}
