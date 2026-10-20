package org.joinmastodon.android

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FriendCardIconContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val androidNamespace = "http://schemas.android.com/apk/res/android"

	@Test
	fun friendCardPngPreservesTheExactOriginalRgbaAsset() {
		val asset = File(projectDir, "src/main/res/drawable-nodpi/ic_friend_card.png")
		// Locks the source bytes, including original 1254×1254 dimensions and alpha channel.
		assertEquals("f8408b9fc963661a424ffa2a5deced29222a8759fe10cb073bdcf84a27fbb08a", sha256(asset.readBytes()))
	}

	@Test
	fun chatVectorPreservesBothOriginalSvgPathsAndViewport() {
		val vector = xml("src/main/res/drawable/ic_friend_card_chat.xml")
		assertEquals("vector", vector.tagName)
		assertEquals("24dp", vector.getAttributeNS(androidNamespace, "width"))
		assertEquals("24dp", vector.getAttributeNS(androidNamespace, "height"))
		assertEquals("1024", vector.getAttributeNS(androidNamespace, "viewportWidth"))
		assertEquals("1024", vector.getAttributeNS(androidNamespace, "viewportHeight"))
		val paths = vector.getElementsByTagName("path")
		assertEquals(2, paths.length)
		val pathData = (0 until paths.length).joinToString("\n") { index ->
			val path = paths.item(index) as Element
			assertEquals("@color/fluent_default_icon_tint", path.getAttributeNS(androidNamespace, "fillColor"))
			path.getAttributeNS(androidNamespace, "pathData")
		}
		// SHA-256 of the two original chat.svg d attributes, joined by one newline.
		assertEquals("48d6280a9ac9982c839f2a4a31809ca155e3be442e569009aab5a115b874dbf6", sha256(pathData.toByteArray(Charsets.UTF_8)))
	}

	@Test
	fun functionalMenuEntriesUseTheDedicatedFriendCardResource() {
		mapOf(
			"home_overflow" to "friend_card_feed",
			"profile_own" to "my_friend_cards",
			"compose" to "compose_insert_friend_card",
			"compose_edit" to "compose_insert_friend_card",
		).forEach { (menu, id) ->
			val items = xml("src/main/res/menu/$menu.xml").getElementsByTagName("item")
			val item = (0 until items.length).map { items.item(it) as Element }
				.single { it.getAttributeNS(androidNamespace, "id") == "@+id/$id" }
			assertEquals("@drawable/ic_friend_card", item.getAttributeNS(androidNamespace, "icon"))
		}
		val javaRoot = "src/main/java/org/joinmastodon/android/fragments"
		val composeRoot = "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation"
		listOf(
			"$javaRoot/HomeTabFragment.java" to "R.id.friend_card_feed",
			"$javaRoot/HomeFragment.java" to "R.id.compose_friend_request",
			"$composeRoot/ComposeFanMenu.kt" to "R.id.compose_friend_request",
		).forEach { (path, id) ->
			val entry = File(projectDir, path).readLines().single { it.contains("MenuItem(") && it.contains(id) }
			assertTrue("$path must bind the dedicated drawable", entry.contains("R.drawable.ic_friend_card"))
		}
		val toolbar = File(projectDir, "$composeRoot/HomeLiquidToolbarView.kt").readText()
		assertTrue(toolbar.contains("data class HomeToolbarComposeMenuItem @JvmOverloads constructor("))
		assertTrue(toolbar.contains("if(item.iconRes!=null)"))
		assertTrue(toolbar.contains("MenuRow(item.iconRes, item.title"))
		val fan = File(projectDir, "$composeRoot/ComposeFanMenu.kt").readText()
		assertTrue(fan.contains("data class ComposeFanMenuItem @JvmOverloads constructor("))
		assertTrue(fan.contains("painterResource(item.iconRes)"))
	}

	private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
		isNamespaceAware = true
	}.newDocumentBuilder().parse(File(projectDir, path)).documentElement

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
