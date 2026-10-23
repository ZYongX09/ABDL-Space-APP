package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.Parcel;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.api.requests.statuses.GetStatusSourceText;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.parceler.Parcels;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Instant;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,27,28}, application=CompatibilityTestApplication.class)
public class NBWParcelCompatibilityTest{
	@Test public void statusRoundTripPreservesTypedReplyAndSyncMetadata(){
		Status status=Status.ofFake("p_42","Body",Instant.EPOCH);
		status.inReplyToId="nbw_3514_10184";
		status.nbwFid=28;
		status.nbwTid=3514L;
		status.nbwSync=new Status.NBWSync();
		status.nbwSync.state="failed";
		status.nbwSync.reason="reply_unconfirmed";
		Status restored=roundTrip(status);
		assertNotSame(status,restored);
		assertEquals(status.id,restored.id);
		assertEquals(status.inReplyToId,restored.inReplyToId);
		assertEquals(status.nbwFid,restored.nbwFid);
		assertEquals(status.nbwTid,restored.nbwTid);
		assertNotSame(status.nbwSync,restored.nbwSync);
		assertEquals("failed",restored.nbwSync.state);
		assertEquals("reply_unconfirmed",restored.nbwSync.reason);
	}

	@Test public void editorSourceBundleRoundTripPreservesOptionalAndExplicitFalseFields(){
		GetStatusSourceText.Response source=new GetStatusSourceText.Response();
		source.id="c_9";
		source.text="Editable text";
		source.spoilerText="";
		source.inReplyToId="p_42";
		source.visibility=StatusPrivacy.PRIVATE;
		source.language="zh";
		source.sensitive=false;
		source.mentalCrisis=true;
		source.geoProvince="Original province";
		source.nbwFid=-1;
		source.nbwTid=3514L;
		source.nbwSyncEnabled=false;
		source.nbwSync=new Status.NBWSync();
		source.nbwSync.state="unsupported";
		source.nbwSync.reason="edit_api_unavailable";
		GetStatusSourceText.Response restored=roundTrip(source);
		assertNotSame(source,restored);
		assertEquals(source.id,restored.id);
		assertEquals(source.text,restored.text);
		assertEquals("",restored.spoilerText);
		assertEquals("p_42",restored.inReplyToId);
		assertEquals(StatusPrivacy.PRIVATE,restored.visibility);
		assertEquals("zh",restored.language);
		assertEquals(Boolean.FALSE,restored.sensitive);
		assertEquals(Boolean.TRUE,restored.mentalCrisis);
		assertEquals("Original province",restored.geoProvince);
		assertNull(restored.geoCity);
		assertEquals(Integer.valueOf(-1),restored.nbwFid);
		assertEquals(Long.valueOf(3514),restored.nbwTid);
		assertEquals(Boolean.FALSE,restored.nbwSyncEnabled);
		assertEquals("unsupported",restored.nbwSync.state);
		assertEquals("edit_api_unavailable",restored.nbwSync.reason);
	}

	private <T> T roundTrip(T value){
		Bundle bundle=new Bundle();
		bundle.putParcelable("value",Parcels.wrap(value));
		Parcel parcel=Parcel.obtain();
		try{
			parcel.writeBundle(bundle);
			byte[] data=parcel.marshall();
			parcel.setDataPosition(0);
			parcel.unmarshall(data,0,data.length);
			parcel.setDataPosition(0);
			Bundle restored=parcel.readBundle(getClass().getClassLoader());
			assertNotNull(restored);
			return Parcels.unwrap(restored.getParcelable("value"));
		}finally{
			parcel.recycle();
		}
	}
}
