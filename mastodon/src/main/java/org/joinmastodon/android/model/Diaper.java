package org.joinmastodon.android.model;

import org.parceler.Parcel;

import java.util.List;

@Parcel
public class Diaper extends BaseModel {
	public int id;
	public String brand;
	public String model;
	public String product_type;
	public int thickness;
	public String absorbency_mfr;
	public String absorbency_adult;
	public int is_baby_diaper;
	public String avg_price;
	public double avg_score;
	public double base_score;
	public int rating_count;
	public int feeling_count;
	public String brand_logo;
	public boolean brand_invert_dark;
	public boolean brand_invert_light;
	public List<DiaperSize> sizes;
	public List<String> images;
}
