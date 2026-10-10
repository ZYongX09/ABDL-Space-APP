package org.joinmastodon.android.model.map;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.BaseModel;

import java.util.List;

/** DTOs for the first map presence API. No map SDK or rendered map state is represented here. */
public final class MapModels{
	private MapModels(){}

	public static class Consent{
		public boolean accepted;
		public String version, acceptedAt;
	}

	public static class Region{
		public String id, name;
	}

	public static class Point{
		public double lat, lng;
		public Region region;
		public String precisionLevel, locationMode, id;
		public boolean anonymous;
		public Account account;
	}

	public static class Account{
		public String id, username, displayName, avatar;
	}

	public static class Settings extends BaseModel{
		public Consent consent;
		public String visibility, precisionLevel, locationMode;
		public int fuzzRadiusM;
		public boolean anonymous, autoRefresh, active, canViewNationwide, canChooseLocation;
		public String mapScope;
		public Region currentRegion;
		@Override public void postprocess() throws ObjectValidationException{
			if(consent==null || !validVisibility(visibility) || !validPrecision(precisionLevel)
					|| !validLocationMode(locationMode) || fuzzRadiusM<200 || fuzzRadiusM>20_000)
				throw new ObjectValidationException("地图设置无效");
		}
	}

	public static class Presence extends BaseModel{
		public String coordinateSystem;
		public boolean active;
		public Point point;
		public String expiresAt, mapScope;
		public Region currentRegion;
		public boolean canViewNationwide, canChooseLocation;
		@Override public void postprocess() throws ObjectValidationException{
			if(active && point==null) throw new ObjectValidationException("地图位置状态无效");
		}
	}

	public static class PresenceList extends BaseModel{
		public String coordinateSystem, scope, nextCursor;
		public Region region;
		public List<Point> points;
		public List<Object> clusters;
		@Override public void postprocess() throws ObjectValidationException{
			if(!"city".equals(scope) && !"nationwide".equals(scope) || points==null || clusters==null)
				throw new ObjectValidationException("地图位置列表无效");
			for(Point point:points) if(point==null || !Double.isFinite(point.lat) || !Double.isFinite(point.lng))
				throw new ObjectValidationException("地图位置点无效");
		}
	}

	public static boolean validVisibility(String value){ return "public".equals(value) || "friends".equals(value) || "hidden".equals(value); }
	public static boolean validPrecision(String value){ return "city".equals(value) || "5km".equals(value) || "1km".equals(value) || "200m".equals(value); }
	public static boolean validLocationMode(String value){ return "device".equals(value) || "pinned".equals(value); }
}
