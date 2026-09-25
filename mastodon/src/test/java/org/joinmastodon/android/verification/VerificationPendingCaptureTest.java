package org.joinmastodon.android.verification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class VerificationPendingCaptureTest{
	private static final String SESSION="123e4567-e89b-42d3-a456-426614174000";
	private Context context;

	@Before
	public void setUp(){
		context=RuntimeEnvironment.getApplication();
		VerificationImageProcessor.deleteTree(new java.io.File(context.getNoBackupFilesDir(), "verification"));
	}

	@After
	public void tearDown(){
		VerificationImageProcessor.deleteTree(new java.io.File(context.getNoBackupFilesDir(), "verification"));
	}

	@Test
	public void savesAndRestoresPendingCapture() throws Exception{
		VerificationPendingCapture expected=new VerificationPendingCapture(SESSION, "123456", "2026-09", "拍摄要求", 2_000_000_000L, 5L*1024L*1024L);
		expected.save(context);
		assertEquals(expected, VerificationPendingCapture.load(context, SESSION));
		assertEquals(expected, VerificationPendingCapture.findActive(context, 1_900_000_000L));
	}

	@Test
	public void expiredCaptureIsRemoved(){
		try{
			new VerificationPendingCapture(SESSION, "123456", "2026-09", "拍摄要求", 10L, 5L*1024L*1024L).save(context);
		}catch(Exception error){
			throw new AssertionError(error);
		}
		assertNull(VerificationPendingCapture.findActive(context, 11L));
		assertNull(VerificationPendingCapture.load(context, SESSION));
	}
}
