package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class AlbumInviteLinkTest{
	private static final String TOKEN="0123456789abcdef".repeat(4);
	private static final String URL="https://abdl-space.top/album-invite/"+TOKEN;

	@Test public void acceptsOnlyCanonicalHttpsSiteAndPath(){
		assertEquals(TOKEN, AlbumInviteLink.parseToken(Uri.parse(URL)));
		assertEquals(TOKEN, AlbumInviteLink.parseToken(Uri.parse(URL.replace(".top/", ".top:443/"))));
	}

	@Test public void rejectsOtherHostsPortsCredentialsAndOpaqueUrls(){
		for(String value:new String[]{URL.replace("https:", "http:"), URL.replace("abdl-space.top", "evil.test"),
				URL.replace("abdl-space.top", "abdl-space.top.evil.test"), URL.replace("abdl-space.top", "user@abdl-space.top"),
				URL.replace("abdl-space.top", "abdl-space.top:8443"), "abdl-space:album-invite/"+TOKEN,
				"javascript:alert(1)", "https://localhost/album-invite/"+TOKEN})
			assertNull(value, AlbumInviteLink.parseToken(Uri.parse(value)));
	}

	@Test public void rejectsNonCanonicalTokensAndPathTricks(){
		for(String value:new String[]{URL+"?next=evil", URL+"#fragment", URL+"/", URL.replace(TOKEN, TOKEN.toUpperCase()),
				URL.replace(TOKEN, TOKEN.substring(1)), URL.replace("/album-invite/", "/album-invite//"),
				URL.replace("/album-invite/", "/%61lbum-invite/"), URL.replace("/album-invite/", "/x/../album-invite/")})
			assertNull(value, AlbumInviteLink.parseToken(Uri.parse(value)));
		assertNull(AlbumInviteLink.parseToken(null));
	}
}
