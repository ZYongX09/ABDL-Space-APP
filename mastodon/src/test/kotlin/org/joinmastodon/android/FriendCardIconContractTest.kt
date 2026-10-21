package org.joinmastodon.android

import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FriendCardIconContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val androidNamespace = "http://schemas.android.com/apk/res/android"

	@Test
	fun friendCardVectorPreservesTheTracedOriginalAlphaSilhouette() {
		val vector = xml("src/main/res/drawable/ic_friend_card.xml")
		assertEquals("vector", vector.tagName)
		assertEquals("24dp", vector.getAttributeNS(androidNamespace, "width"))
		assertEquals("24dp", vector.getAttributeNS(androidNamespace, "height"))
		assertEquals("24", vector.getAttributeNS(androidNamespace, "viewportWidth"))
		assertEquals("24", vector.getAttributeNS(androidNamespace, "viewportHeight"))
		assertEquals(0, vector.getElementsByTagName("group").length)
		assertEquals(0, vector.getElementsByTagName("clip-path").length)
		val paths = vector.getElementsByTagName("path")
		assertEquals(5, paths.length)
		val pathData = (0 until paths.length).map { index ->
			val path = paths.item(index) as Element
			assertEquals("@color/fluent_default_icon_tint", path.getAttributeNS(androidNamespace, "fillColor"))
			assertEquals("nonZero", path.getAttributeNS(androidNamespace, "fillType"))
			assertFalse(path.hasAttributeNS(androidNamespace, "strokeColor"))
			assertFalse(path.hasAttributeNS(androidNamespace, "fillAlpha"))
			path.getAttributeNS(androidNamespace, "pathData")
		}
		// Trace of original SHA-256 f8408b9fc963661a424ffa2a5deced29222a8759fe10cb073bdcf84a27fbb08a,
		// alpha >= 128, uniformly fitted from (254,239)-(997,1002) into 22 of 24 units.
		// Native aligned render: silhouette IoU 99.59198%, tight-crop alpha MAE 0.66953%.
		// Pins all curves, winding/cutout, relative placement, and ~1dp optical margins.
		assertEquals("74d7c41c34be4ba8c431bf13f5a7e3624c771caa83826e40a8817432691a3722",
			sha256(pathData.joinToString("\n").toByteArray(Charsets.UTF_8)))
		assertEquals(6, pathData.sumOf { data -> data.count { it == 'M' || it == 'm' } })
		assertEquals(6, pathData.sumOf { data -> data.count { it == 'z' || it == 'Z' } })
		assertFalse("A density-qualified bitmap must not shadow the vector",
			File(projectDir, "src/main/res").walkTopDown().any {
				it.isFile && it.parentFile.name.startsWith("drawable") &&
					it.nameWithoutExtension == "ic_friend_card" && it.extension != "xml"
			})
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
	fun onlyTheDedicatedFriendCardNavigationEntryUsesTheResource() {
		mapOf(
			"home_overflow" to "friend_card_feed",
			"profile_own" to "my_friend_cards",
			"compose" to "compose_insert_friend_card",
			"compose_edit" to "compose_insert_friend_card",
		).forEach { (menu, id) ->
			val items = xml("src/main/res/menu/$menu.xml").getElementsByTagName("item")
			assertFalse("$menu must not retain the superseded friend-card entry", (0 until items.length).any {
				(items.item(it) as Element).getAttributeNS(androidNamespace, "id") == "@+id/$id"
			})
		}
		val javaRoot = "src/main/java/org/joinmastodon/android/fragments"
		val composeRoot = "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation"
		listOf("$javaRoot/HomeFragment.java", "$composeRoot/ComposeFanMenu.kt").forEach { path ->
			val lines = File(projectDir, path).readLines()
			val entry = lines.single { it.contains("MenuItem(") && it.contains("R.id.compose_friend_card,") }
			assertTrue("$path must bind the dedicated drawable", entry.contains("R.drawable.ic_friend_card"))
			assertTrue("$path must use the dedicated title", entry.contains("R.string.compose_menu_friend_card"))
			val request = lines.single { it.contains("MenuItem(") && it.contains("R.id.compose_friend_request,") }
			assertTrue("$path must keep the original request icon", request.contains("ContactsBook"))
			assertFalse("Friend requests must not reuse the card drawable", request.contains("R.drawable.ic_friend_card"))
		}
		val toolbar = File(projectDir, "$composeRoot/HomeLiquidToolbarView.kt").readText()
		assertTrue(toolbar.contains("data class HomeToolbarComposeMenuItem @JvmOverloads constructor("))
		assertTrue(toolbar.contains("if(item.iconRes!=null)"))
		assertTrue(toolbar.contains("MenuRow(item.iconRes, item.title"))
		val fan = File(projectDir, "$composeRoot/ComposeFanMenu.kt").readText()
		assertTrue(fan.contains("data class ComposeFanMenuItem @JvmOverloads constructor("))
		assertTrue(fan.contains("painterResource(item.iconRes)"))
	}

	@Test
	fun composerFriendCardIconMatchesTheOther24dpToolbarIcons() {
		val layout = xml("src/main/res/layout/fragment_compose.xml")
		val scrollers = layout.getElementsByTagName("HorizontalScrollView")
		assertEquals(1, scrollers.length)
		val scroller = scrollers.item(0) as Element
		assertEquals("0dp", scroller.getAttributeNS(androidNamespace, "layout_width"))
		assertEquals("1", scroller.getAttributeNS(androidNamespace, "layout_weight"))
		val buttons = scroller.getElementsByTagName("ImageButton")
		val toolbarButtons = (0 until buttons.length).map { buttons.item(it) as Element }
		assertEquals(setOf("btn_media", "btn_poll", "btn_emoji", "btn_spoiler", "btn_language", "compose_insert_friend_card"),
			toolbarButtons.map { it.getAttributeNS(androidNamespace, "id").removePrefix("@+id/") }.toSet())
		assertEquals(6, toolbarButtons.size)
		toolbarButtons.forEach { button ->
			assertEquals("36dp", button.getAttributeNS(androidNamespace, "layout_width"))
			assertEquals("36dp", button.getAttributeNS(androidNamespace, "layout_height"))
			assertEquals("6dp", button.getAttributeNS(androidNamespace, "padding"))
			assertEquals("centerInside", button.getAttributeNS(androidNamespace, "scaleType"))
			assertEquals("@color/compose_button", button.getAttributeNS(androidNamespace, "tint"))
			assertEquals("src_in", button.getAttributeNS(androidNamespace, "tintMode"))
		}
		val card = toolbarButtons.single { it.getAttributeNS(androidNamespace, "id") == "@+id/compose_insert_friend_card" }
		assertEquals("@drawable/ic_friend_card", card.getAttributeNS(androidNamespace, "src"))
		assertEquals("@string/friend_card_compose_insert", card.getAttributeNS(androidNamespace, "contentDescription"))
		val textViews = layout.getElementsByTagName("TextView")
		val counter = (0 until textViews.length).map { textViews.item(it) as Element }
			.single { it.getAttributeNS(androidNamespace, "id") == "@+id/char_counter" }
		assertEquals("The counter must stay pinned outside the scrolling actions", scroller.parentNode, counter.parentNode)
	}

	private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
		isNamespaceAware = true
	}.newDocumentBuilder().parse(File(projectDir, path)).documentElement

	private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
		.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
