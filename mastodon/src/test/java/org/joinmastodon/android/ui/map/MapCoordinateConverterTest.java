package org.joinmastodon.android.ui.map;

import org.junit.Test;

import static org.junit.Assert.*;

public class MapCoordinateConverterTest{
	@Test public void mainlandCoordinateHasExpectedGcjOffset(){
		var converted=MapCoordinateConverter.wgs84ToGcj02(39.908823,116.397470);
		assertEquals(39.9102265,converted.latitude(),0.000002);
		assertEquals(116.4037136,converted.longitude(),0.000002);
	}
	@Test public void mainlandCoordinatesRoundTrip(){
		for(double[] location:new double[][]{{39.908823,116.397470},{31.2304,121.4737},{22.5431,114.0579}}){
			var gcj=MapCoordinateConverter.wgs84ToGcj02(location[0],location[1]);
			var inverse=MapCoordinateConverter.gcj02ToWgs84(gcj.latitude(),gcj.longitude());
			assertEquals(location[0],inverse.latitude(),0.0000001);
			assertEquals(location[1],inverse.longitude(),0.0000001);
			var baidu=MapCoordinateConverter.wgs84ToBd09(location[0],location[1]);
			var recovered=MapCoordinateConverter.bd09ToWgs84(baidu.latitude(),baidu.longitude());
			assertEquals(location[0],recovered.latitude(),0.000002);
			assertEquals(location[1],recovered.longitude(),0.000002);
		}
	}
	@Test public void overseasAndDateLineStayUnchanged(){
		for(double[] location:new double[][]{{51.5074,-0.1278},{40.7128,-74.0060},{-10,180},{-10,-180}}){
			var gcj=MapCoordinateConverter.wgs84ToGcj02(location[0],location[1]);
			var baidu=MapCoordinateConverter.wgs84ToBd09(location[0],location[1]);
			assertEquals(location[0],gcj.latitude(),0); assertEquals(location[1],gcj.longitude(),0);
			assertEquals(location[0],baidu.latitude(),0); assertEquals(location[1],baidu.longitude(),0);
		}
	}
	@Test public void invalidCoordinatesAreRejected(){
		for(double[] location:new double[][]{{Double.NaN,0},{0,Double.POSITIVE_INFINITY},{91,0},{0,181}}){
			try{ MapCoordinateConverter.wgs84ToGcj02(location[0],location[1]); fail("Coordinate accepted"); }
			catch(IllegalArgumentException expected){}
		}
	}
}
