package org.joinmastodon.android.novel.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelStore
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Entry-to-production-center rendering. The offline transport is the only substituted business
 * boundary: eligibility/application UI and click gates are the real NovelAppContent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCreatorEntryUiTest {
    private val fixture = NovelScreenshotFixture("creator-entry")
    private val host get() = fixture.host
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private var created = 0
    private var read = 0
    private lateinit var transport: TestNovelTransport
    private lateinit var vm: NovelAppViewModel

    @After fun cleanup() = host.runOnUiThread { models.clear() }

    private fun open(active: Boolean) {
        host.runOnUiThread {
            transport = TestNovelTransport { _, path, _, _ ->
                if (path == "/creator") NovelAppTestFixtures.creatorJson(active = active, eligible = active)
                else NovelAppTestFixtures.page()
            }
            vm = NovelAppViewModel({ NovelRepository(transport) }, MemoryNovelSearchHistory())
            models.put("creator-entry", vm)
            bindNovelAppEntry(vm, NovelScreenshotFixture.ACCOUNT, NovelAppTestFixtures.author,
                NovelAppActivity.creatorIntent(host.activity, NovelScreenshotFixture.ACCOUNT), false)
        }
        fixture.show(host) {
            val state by vm.state.collectAsState()
            NovelAppContent(state, vm, false, {}, { _, _ -> read++ }, { _, _, _ -> created++ }, {}, {}, {}, {}, {})
        }
        assertEquals(NovelPage.Creator, vm.state.value.page)
        host.onNodeWithText("创作者中心").assertIsDisplayed()
        assertEquals(0, read)
        assertEquals(0, created)
        assertTrue(transport.requests.none { it.method != "GET" || it.path == "/owner/works" })
    }

    @Test fun activeCreatorEntryRendersTheCenterNotOwnerWorksOrReader() {
        open(true)
        host.onNodeWithText("已开通创作者").assertIsDisplayed()
        host.onNodeWithText("新建作品").assertIsEnabled()
        host.onNodeWithText("上传文稿").assertIsEnabled()
        host.onNodeWithText("新建作品").performClick()
        assertEquals(1, created)
        assertEquals(0, read)
    }

    @Test fun unqualifiedNonCreatorEntryShowsOriginalApplicationWithDisabledApplyAndNoAutoGrant() {
        open(false)
        host.onNodeWithText("已完成 1 / 3 项").assertIsDisplayed()
        host.onNodeWithText("开通创作者身份").performScrollTo().assertIsNotEnabled()
        assertFalse(vm.state.value.creator.value!!.active)
        assertTrue(transport.requests.none { it.path == "/creator/apply" })
        assertEquals(0, created)
        assertEquals(0, read)
    }
}
