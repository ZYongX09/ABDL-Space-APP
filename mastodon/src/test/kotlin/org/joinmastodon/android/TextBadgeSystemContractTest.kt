package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBadgeSystemContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun timelineHeadersRenderTextBadgeInsteadOfVerifiedCircle() {
		val header = source("src/main/java/org/joinmastodon/android/ui/displayitems/HeaderStatusDisplayItem.java")
		val compact = source("src/main/java/org/joinmastodon/android/ui/displayitems/CompactHeaderStatusDisplayItem.java")

		for (file in listOf(header, compact)) {
			// 新版：account.badge 渲染为后台指定颜色的文字徽章
			assertTrue(file.contains("item.user.badge!=null && !TextUtils.isEmpty(item.user.badge.name)"))
			assertTrue(file.contains("new org.joinmastodon.android.ui.text.BadgeSpan(item.user.badge)"))
			// 旧版 verified 圆形图标叠加已删除
			assertFalse(file.contains("ic_badge_verified_circle"))
			// 无头像账号回落默认头像
			assertTrue(file.contains("R.drawable.default_avatar"))
		}
		assertTrue(header.contains("org.joinmastodon.android.sponsors.SponsorUsername.apply(name, item.user, item.accountID)"))
	}

	@Test
	fun badgeSpanKeepsColorContract() {
		val span = source("src/main/java/org/joinmastodon/android/ui/text/BadgeSpan.java")
		assertTrue(span.contains("static int foregroundColorFor(int bgColor)"))
		assertTrue(span.contains("0xFF1B1B1B"))
		assertTrue(span.contains("0xFFFFFFFF"))
		// 颜色契约必须为 #rrggbb，否则回退紫色
		assertTrue(span.contains("0xFF7C4DFF"))
	}

	@Test
	fun profileRestoresColoredBadgeSelectorWithDisplayToggle() {
		val profile = source("src/main/java/org/joinmastodon/android/fragments/ProfileFragment.java")

		// 后台颜色文字徽章 + displayed 状态（✓）
		assertTrue(profile.contains("boolean displayed=badge.has(\"displayed\") && badge.get(\"displayed\").getAsBoolean()"))
		assertTrue(profile.contains("pill.setText(bName+(displayed ? \" ✓\" : \"\"))"))
		assertTrue(profile.contains("int bgColor=parseBadgeColor(color)"))
		assertTrue(profile.contains("pill.setTextColor(org.joinmastodon.android.ui.text.BadgeSpan.foregroundColorFor(bgColor))"))
		// 本人选择/取消展示徽章 API
		assertTrue(profile.contains("setDisplayedBadge(key, currentlyDisplayed ? null : key)"))
		assertTrue(profile.contains("\"https://api.abdl-space.top/api/users/\"+account.id+\"/badges/display\""))
		assertTrue(profile.contains("badge_key"))
		// 他人主页只读：非本人不展示选择器
		assertTrue(profile.contains("if(!isOwnProfile)"))
		// 旧 verified 圆形图标叠加已删除
		assertFalse(profile.contains("ic_badge_verified_circle"))
		assertFalse(profile.contains("BadgeExplainerSheet(getActivity(), bName, description"))
	}

	@Test
	fun profileBadgeDisplayStaysDecoupledFromVerificationAndSponsor() {
		val profile = source("src/main/java/org/joinmastodon/android/fragments/ProfileFragment.java")
		val account = source("src/main/java/org/joinmastodon/android/model/Account.java")

		// 宝宝认证是资料字段，赞助者投影走 SponsorUsername；徽章只来自 account.badge 与徽章列表
		assertTrue(profile.contains("account.babyVerification!=null && account.babyVerification.verified"))
		assertTrue(profile.contains("org.joinmastodon.android.sponsors.SponsorUsername.apply(name, account, accountID)"))
		assertTrue(profile.contains("public void onSponsorChanged(org.joinmastodon.android.events.SponsorChangedEvent event)"))
		assertTrue(account.contains("public Badge badge;"))
		assertTrue(account.contains("public boolean verified;"))
	}

	@Test
	fun newBadgePopupStillUsesTextPill() {
		val sheet = source("src/main/java/org/joinmastodon/android/ui/sheets/NewBadgeSheet.java")
		assertTrue(sheet.contains("BadgeSpan.foregroundColorFor"))
		assertTrue(sheet.contains("GradientDrawable"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
