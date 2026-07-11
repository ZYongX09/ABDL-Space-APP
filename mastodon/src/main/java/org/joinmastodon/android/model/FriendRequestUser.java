package org.joinmastodon.android.model;

import org.parceler.Parcel;

@Parcel
public class FriendRequestUser extends BaseModel {
	public String username;
	public String avatar;
	public String display_name;
}
