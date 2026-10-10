package org.joinmastodon.android.ui.map;

public final class MapGlassPolicy{
	private MapGlassPolicy(){}
	public static boolean supportsLiquid(int sdk,boolean hardware,boolean preference,boolean sampleReady){
		return sdk>=33 && hardware && preference && sampleReady;
	}
	public static float surfaceAlpha(boolean liquid,boolean largePanel){
		return liquid ? (largePanel ? .38f : .26f) : .96f;
	}
}
