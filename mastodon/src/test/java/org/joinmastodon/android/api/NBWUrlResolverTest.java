package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.ui.utils.NBWImageRequests;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
public class NBWUrlResolverTest{
	@Test public void exactTrustedSourcesPreserveEncodedSignedPathQueryAndFragment(){
		var backup=NBWEndpointFixtures.endpoints("backup","r2","full");
		String tail="/data/%2FAb%20Cd.jpg?sign=a%2Bb%2F%3D&x=1&x=2#Section%20A";
		assertEquals(NBWEndpoints.BACKUP+tail,NBWUrlResolver.resolve(NBWEndpoints.PRIMARY+tail,backup,true));
		assertEquals(NBWEndpoints.BACKUP+tail,NBWUrlResolver.resolve(NBWEndpoints.BACKUP+tail,backup,false));
		assertEquals(NBWEndpoints.PRIMARY+tail,NBWUrlResolver.resolve(NBWEndpoints.BACKUP+tail,NBWEndpointFixtures.primary(),false));
	}
	@Test public void thirdPartyAndCredentialOrEncodedHostInputsNeverBecomeTrusted(){
		for(String value:List.of("https://third.example/p?q=1","http://www.newbabyworld.top/a","https://user@www.newbabyworld.top/a",
				"https://www.newbabyworld.top:443/a","https://www.newbabyworld.top.evil/a","https://www.newbabyworld.top./a",
				"https://%77ww.newbabyworld.top/a","https://www.newbabyworld.top\\@evil/a","https://WWW.newbabyworld.top/a","//www.newbabyworld.top/a")){
			assertFalse(value,NBWUrlResolver.isTrusted(value)); assertEquals(value,NBWUrlResolver.resolve(value,NBWEndpointFixtures.primary(),true));
		}
		assertEquals("",NBWUrlResolver.resolve(NBWEndpoints.PRIMARY+"/a",null,true));
	}
	@Test public void lazyCachedImageUsesCurrentOriginAndRevisionPartitionWithoutChangingSource() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		try(var fixtures=new NBWEndpointFixtures()){
			UrlImageLoaderRequest source=new UrlImageLoaderRequest(NBWEndpoints.PRIMARY+"/avatar.php?uid=42&size=middle",50,50);
			var first=NBWImageRequests.current(source); assertNotNull(first);
			fixtures.publish("backup","endpoint-b","full"); var next=NBWImageRequests.current(source);
			assertEquals(NBWEndpoints.BACKUP+"/avatar.php?uid=42&size=middle",((UrlImageLoaderRequest)next).uri.toString());
			assertNotEquals(first.getDiskCacheKey(),next.getDiskCacheKey()); assertFalse(NBWImageRequests.accepts(first));
			fixtures.publish("primary","endpoint-c","full"); var returned=NBWImageRequests.current(source);
			assertNotEquals(first.getMemoryCacheKey(),returned.getMemoryCacheKey());
			assertEquals(NBWEndpoints.PRIMARY+"/avatar.php?uid=42&size=middle",source.uri.toString());
			fixtures.publish("primary","endpoint-d","local_only"); assertNull(NBWImageRequests.current(source));
			UrlImageLoaderRequest third=new UrlImageLoaderRequest("https://third.example/avatar"); assertSame(third,NBWImageRequests.current(third));
		}
	}
}
