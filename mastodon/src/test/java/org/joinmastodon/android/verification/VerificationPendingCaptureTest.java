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
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=26)
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
	public void rawOnlyCaptureIsRecoverableAndEmptyOrSymlinkIsNot() throws Exception{
		VerificationPendingCapture value=new VerificationPendingCapture(SESSION, "123456", "v1", "requirement", 2_000_000_000L, 5L*1024L*1024L);
		value.save(context);
		assertEquals(false, value.hasRecoverablePhoto(context));
		java.nio.file.Files.write(value.rawFile(context).toPath(), new byte[]{1,2,3});
		assertEquals(true, value.hasRecoverablePhoto(context));
		assertEquals(false, value.photoFile(context).exists());
		value.rawFile(context).delete();
		java.io.File outside=java.io.File.createTempFile("outside", ".jpg", context.getCacheDir());
		java.nio.file.Files.write(outside.toPath(), new byte[]{1});
		java.nio.file.Files.createSymbolicLink(value.rawFile(context).toPath(), outside.toPath());
		assertEquals(false, value.hasRecoverablePhoto(context));
		value.rawFile(context).delete(); outside.delete();
	}

	@Test
	public void trustedPrivateRootAliasPreservesMetadataAndPhotoRecovery() throws Exception{
		java.io.File alias=new java.io.File(context.getCacheDir(), "pending-root-alias");
		java.nio.file.Files.createSymbolicLink(alias.toPath(), context.getNoBackupFilesDir().toPath());
		Context aliased=new android.content.ContextWrapper(context){
			@Override public java.io.File getNoBackupFilesDir(){ return alias; }
		};
		try{
			VerificationPendingCapture value=new VerificationPendingCapture(SESSION, "123456", "v1", "requirement", 2_000_000_000L, 5L*1024*1024);
			value.save(aliased);
			assertEquals(value, VerificationPendingCapture.load(aliased, SESSION));
			java.nio.file.Files.write(value.rawFile(aliased).toPath(), new byte[]{1});
			assertEquals(true, value.hasRecoverablePhoto(aliased));
			value.rawFile(aliased).delete();
			java.nio.file.Files.write(value.photoFile(aliased).toPath(), new byte[]{1});
			assertEquals(true, value.hasRecoverablePhoto(aliased));
		}finally{ java.nio.file.Files.delete(alias.toPath()); }
	}

	@Test
	public void linksWithinPrivateRootAndDirectoryLinksAreStillRejected() throws Exception{
		VerificationPendingCapture value=new VerificationPendingCapture(SESSION, "123456", "v1", "requirement", 2_000_000_000L, 5L*1024*1024);
		value.save(context);
		java.io.File target=new java.io.File(VerificationPendingCapture.root(context, SESSION), "target.jpg");
		java.nio.file.Files.write(target.toPath(), new byte[]{1});
		java.nio.file.Files.createSymbolicLink(value.rawFile(context).toPath(), target.toPath());
		assertEquals(false, value.hasRecoverablePhoto(context));
		value.rawFile(context).delete();
		java.io.File traversal=new java.io.File(VerificationPendingCapture.root(context, SESSION), "../"+SESSION+"/target.jpg");
		assertEquals(false, org.joinmastodon.android.ui.media.MediaCameraContract.isControlledFile(context, traversal));
		java.io.File other=new java.io.File(context.getCacheDir(), "outside-session");
		other.mkdirs();
		VerificationImageProcessor.deleteTree(VerificationPendingCapture.root(context, SESSION));
		java.nio.file.Files.createSymbolicLink(VerificationPendingCapture.root(context, SESSION).toPath(), other.toPath());
		try{
			assertNull(VerificationPendingCapture.load(context, SESSION));
			org.junit.Assert.assertThrows(java.io.IOException.class, ()->value.save(context));
			java.nio.file.Files.write(new java.io.File(other, "raw-0.jpg").toPath(), new byte[]{1});
			assertEquals(false, value.hasRecoverablePhoto(context));
		}finally{
			VerificationPendingCapture.root(context, SESSION).delete();
			VerificationImageProcessor.deleteTree(other);
		}
	}

	@Test
	public void cleanupDoesNotFollowChildDirectoryLink() throws Exception{
		VerificationPendingCapture value=new VerificationPendingCapture(SESSION, "123456", "v1", "requirement", 2_000_000_000L, 5L*1024*1024);
		value.save(context);
		java.io.File outside=new java.io.File(context.getCacheDir(), "cleanup-outside"); outside.mkdirs();
		java.io.File untouched=new java.io.File(outside, "private.jpg");
		java.nio.file.Files.write(untouched.toPath(), new byte[]{1});
		try{
			java.nio.file.Files.createSymbolicLink(new java.io.File(VerificationPendingCapture.root(context, SESSION), "linked-dir").toPath(), outside.toPath());
			value.delete(context);
			assertEquals(true, untouched.isFile());
		}finally{ VerificationImageProcessor.deleteTree(outside); }
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
