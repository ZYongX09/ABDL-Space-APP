package org.joinmastodon.android.sponsors;

/** WCAG relative luminance, independent of android.graphics for JVM tests. */
public final class SponsorContrast{
	private SponsorContrast(){}
	private static double channel(int value){ double c=value/255.0; return c<=0.04045 ? c/12.92 : Math.pow((c+0.055)/1.055, 2.4); }
	private static double luminance(int color){ return 0.2126*channel(color>>16&255)+0.7152*channel(color>>8&255)+0.0722*channel(color&255); }
	public static double ratio(int foreground, int background){ double a=luminance(foreground), b=luminance(background); return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05); }
	public static Integer parse(String value){
		if(value==null || !value.matches("#[0-9a-fA-F]{6}")) return null;
		return (int)(0xff000000L | Long.parseLong(value.substring(1), 16));
	}
	/** Shade toward black or white, retaining the supplied hue until the threshold is met. */
	public static int ensure(int color, int background){
		color|=0xff000000; background|=0xff000000;
		if(ratio(color, background)>=4.5) return color;
		int target=ratio(0xff000000, background)>ratio(0xffffffff, background) ? 0xff000000 : 0xffffffff;
		for(int step=1; step<=255; step++){
			int result=0xff000000;
			for(int shift:new int[]{16, 8, 0}){
				int c=color>>shift&255, t=target>>shift&255;
				result|=(c+(t-c)*step/255)<<shift;
			}
			if(ratio(result, background)>=4.5) return result;
		}
		return target;
	}
	public static boolean active(boolean active, boolean permanent, Long validUntil, long now){
		return active && (permanent || validUntil!=null && validUntil>now);
	}
}
