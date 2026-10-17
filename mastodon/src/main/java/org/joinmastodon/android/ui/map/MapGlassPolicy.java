package org.joinmastodon.android.ui.map;

public final class MapGlassPolicy{
	public static final float BLUR_DP=18f;
	public static final float REFRACTION_HEIGHT_DP=8f;
	public static final float REFRACTION_AMOUNT_DP=10f;
	public static final float HIGHLIGHT_ALPHA=.62f;
	private MapGlassPolicy(){}
	public static boolean supportsLiquid(int sdk,boolean hardware,boolean preference,boolean sampleReady){
		return sdk>=33 && hardware && preference && sampleReady;
	}
	public static float surfaceAlpha(boolean liquid,boolean largePanel){
		return liquid ? (largePanel ? .48f : .35f) : .96f;
	}
	/** Dark theme draws a dark glass regardless of the light map tiles behind it. */
	public static float liquidSurfaceAlpha(boolean darkTheme,boolean largePanel){
		return darkTheme ? (largePanel ? .78f : .72f) : .28f;
	}
}
