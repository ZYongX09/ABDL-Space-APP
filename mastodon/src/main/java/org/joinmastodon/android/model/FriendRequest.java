package org.joinmastodon.android.model;

import org.parceler.Parcel;

import java.util.List;

@Parcel
public class FriendRequest extends BaseModel {
	public String id;
	public String user_id;
	public String title;
	public String looking_for;
	public String description;
	public String status;
	public String created_at;
	public String updated_at;
	public FriendRequestUser user;
	public List<FriendRequestField> fields;
	public int comment_count;
}
