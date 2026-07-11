package org.joinmastodon.android.model;

import org.parceler.Parcel;

@Parcel
public class FriendRequestField extends BaseModel {
	public String id;
	public String request_id;
	public String field_key;
	public String field_value;
	public int is_primary;
	public int sort_order;
}
