package org.joinmastodon.android.model;

import org.parceler.Parcel;

import java.util.List;

@Parcel
public class DiaperReview extends BaseModel {
	public int id;
	public ReviewUser user;
	public int diaper_id;
	public int absorption_score;
	public int comfort_score;
	public int thickness_score;
	public int appearance_score;
	public int value_score;
	public String review;
	public String review_status;
	public String created_at;

	@Parcel
	public static class ReviewUser extends BaseModel {
		public int id;
		public String username;
		public String avatar;
		public String role;
	}
}
