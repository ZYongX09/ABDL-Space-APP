package org.joinmastodon.android.ui.map;

public final class MapCoordinateConverter{
	private static final double AXIS=6378245.0;
	private static final double ECCENTRICITY=0.00669342162296594323;
	private static final double X_PI=Math.PI*3000.0/180.0;

	private MapCoordinateConverter(){}
	public record Coordinate(double latitude, double longitude){
		public Coordinate{
			if(!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
				throw new IllegalArgumentException("Invalid map coordinate");
		}
	}

	public static boolean inChina(double lat, double lng){
		return lng>=72.004 && lng<=137.8347 && lat>=0.8293 && lat<=55.8271;
	}

	public static Coordinate wgs84ToGcj02(double lat, double lng){
		Coordinate input=new Coordinate(lat, lng);
		if(!inChina(lat, lng)) return input;
		double dLat=transformLatitude(lng-105, lat-35);
		double dLng=transformLongitude(lng-105, lat-35);
		double radLat=lat*Math.PI/180;
		double magic=Math.sin(radLat);
		magic=1-ECCENTRICITY*magic*magic;
		double sqrtMagic=Math.sqrt(magic);
		dLat=dLat*180/((AXIS*(1-ECCENTRICITY)/(magic*sqrtMagic))*Math.PI);
		dLng=dLng*180/(AXIS/sqrtMagic*Math.cos(radLat)*Math.PI);
		return new Coordinate(lat+dLat, lng+dLng);
	}

	public static Coordinate gcj02ToWgs84(double lat, double lng){
		Coordinate input=new Coordinate(lat, lng);
		if(!inChina(lat, lng)) return input;
		double wgsLat=lat, wgsLng=lng;
		for(int i=0;i<12;i++){
			Coordinate converted=wgs84ToGcj02(wgsLat, wgsLng);
			double dLat=converted.latitude-lat, dLng=converted.longitude-lng;
			wgsLat-=dLat; wgsLng-=dLng;
			if(Math.max(Math.abs(dLat), Math.abs(dLng))<1e-9) break;
		}
		return new Coordinate(wgsLat, wgsLng);
	}

	public static Coordinate wgs84ToBd09(double lat, double lng){
		Coordinate gcj=wgs84ToGcj02(lat, lng);
		if(!inChina(lat, lng)) return gcj;
		double z=Math.sqrt(gcj.longitude*gcj.longitude+gcj.latitude*gcj.latitude)+0.00002*Math.sin(gcj.latitude*X_PI);
		double theta=Math.atan2(gcj.latitude, gcj.longitude)+0.000003*Math.cos(gcj.longitude*X_PI);
		return new Coordinate(z*Math.sin(theta)+0.006, z*Math.cos(theta)+0.0065);
	}

	public static Coordinate bd09ToWgs84(double lat, double lng){
		Coordinate input=new Coordinate(lat, lng);
		if(!inChina(lat, lng)) return input;
		double x=lng-0.0065, y=lat-0.006;
		double z=Math.sqrt(x*x+y*y)-0.00002*Math.sin(y*X_PI);
		double theta=Math.atan2(y, x)-0.000003*Math.cos(x*X_PI);
		return gcj02ToWgs84(z*Math.sin(theta), z*Math.cos(theta));
	}

	private static double transformLatitude(double x, double y){
		double result=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
		result+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
		result+=(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3;
		return result+(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;
	}

	private static double transformLongitude(double x, double y){
		double result=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
		result+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
		result+=(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3;
		return result+(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;
	}
}
