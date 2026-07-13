package org.joinmastodon.android.model;

import org.parceler.Parcel;

@Parcel
public class RankingItem extends BaseModel {
	public int id;
	public String brand;
	public String model;
	public boolean is_baby_diaper;
	public double avg_score;
	public double base_score;
	public int rating_count;
	public int thickness;
	public String absorbency_adult;
	public String product_type;
}
