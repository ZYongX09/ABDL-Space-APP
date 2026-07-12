package org.joinmastodon.android.model;

import org.parceler.Parcel;

@Parcel
public class DiaperSize extends BaseModel {
	public String label;
	public int waist_min;
	public int waist_max;
	public int hip_min;
	public int hip_max;
}
