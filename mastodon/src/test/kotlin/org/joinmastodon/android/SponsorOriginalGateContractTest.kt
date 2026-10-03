package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorOriginalGateContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun gateCoversFullAuthorizationFlow() {
		val gate = source("src/main/java/org/joinmastodon/android/sponsors/SponsorOriginalGate.java")
		assertTrue(gate.contains("SponsorAvailability.check(accountID"))
		assertTrue(gate.contains("SponsorRequest.catalog()"))
		assertTrue(gate.contains("SponsorRequest.me()"))
		assertTrue(gate.contains("SponsorRequest.authorize(a.operation, a.media, noticeVersion)"))
		assertTrue(gate.contains("\"quota_exhausted\""))
		assertTrue(gate.contains("\"notice_required\""))
		assertTrue(gate.contains("\"operation_expired\""))
		assertTrue(gate.contains("\"sponsors_disabled\""))
		assertTrue(gate.contains("new SponsorNoticeSheet(activity, title, body, action"))
		assertTrue(gate.contains("tickets.hasRetry(accountID, a.media"))
		assertTrue(gate.contains("tickets.authorized(accountID, a.media, a.operation"))
		// 账号切换立即取消授权流
		assertTrue(gate.contains("\"lastActiveAccount\".equals(key)"))
	}

	@Test
	fun photoViewerDefaultsToPreviewAndGatesOriginal() {
		val viewer = source("src/main/java/org/joinmastodon/android/ui/photoviewer/PhotoViewer.java")
		val layout = source("src/main/res/layout/photo_viewer_ui.xml")

		assertTrue(layout.contains("android:id=\"@+id/btn_view_original\""))
		assertTrue(layout.contains("@drawable/bg_view_original_pill"))
		assertTrue(viewer.contains("private SponsorOriginalGate originalGate"))
		assertTrue(viewer.contains("originalGate.authorize(att.id, att.url, download)"))
		assertTrue(viewer.contains("R.string.verifying_original"))
		assertTrue(viewer.contains("R.string.downloading_original"))
		assertTrue(viewer.contains("if(isOriginalCached(item))"))
		// 无预览时绝不静默拉取未授权原图
		assertTrue(viewer.contains("if(TextUtils.isEmpty(item.previewUrl))"))
		assertTrue(viewer.contains("else originalGate.authorize(att.id, att.url, download)"))
		assertTrue(viewer.contains("private static boolean isAcceptableImageUrl(String url)"))
		// 翻页/关闭/账号切换都会使未完成的原图工作失效
		assertTrue(viewer.contains("if(currentIndex!=index) invalidateOriginalWork()"))
		assertTrue(viewer.contains("if(\"lastActiveAccount\".equals(key)) invalidateOriginalWork()"))
		// 保存图片同样经赞助门禁
		assertTrue(viewer.contains("saveViaDownloadManager"))
	}

	@Test
	fun viewerKeepsAndroid8CompatibleStorageFlow() {
		val viewer = source("src/main/java/org/joinmastodon/android/ui/photoviewer/PhotoViewer.java")
		assertTrue(viewer.contains("Build.VERSION.SDK_INT<29"))
		assertTrue(viewer.contains("WRITE_EXTERNAL_STORAGE"))
		assertTrue(viewer.contains("pendingPermissionAttachment"))
	}

	@Test
	fun editorMediaStillUsesUnifiedPhotoViewer() {
		// 编辑器图片不绕过统一 PhotoViewer/门禁：ComposeMediaViewController 不自带原图下载
		val compose = source("src/main/java/org/joinmastodon/android/ui/viewcontrollers/ComposeMediaViewController.java")
		assertFalse(compose.contains("SponsorOriginalGate"))
		assertFalse(compose.contains("btn_view_original"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
