package org.joinmastodon.android.novel.app

import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.NovelJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
class NovelOriginalAuthorProjectionTest {
    @Test fun repostBylineNeverImpersonatesTheUploaderAccount() {
        val payload = NovelAppTestFixtures.workJson().put("work_type", "repost").put("original_author", "原作作者")
        val work = NovelJson.work(payload)
        assertEquals("原作作者", work.displayAuthorName)
        assertEquals("原作作者", work.originalAuthor)
        assertEquals(NovelAppTestFixtures.author.id, work.author.id)
        assertEquals(NovelAppTestFixtures.author.username, work.author.username)
    }

    @Test fun originalWorkAndHistoricalRepostHaveExplicitCompatibleBylines() {
        val payload = NovelAppTestFixtures.workJson()
        assertEquals(NovelAppTestFixtures.author.name, NovelJson.work(payload).displayAuthorName)
        payload.put("work_type", "repost").remove("original_author")
        assertEquals("原作者未填写", NovelJson.work(payload).displayAuthorName)
        payload.put("original_author", JSONObject.NULL)
        assertEquals("原作者未填写", NovelJson.work(payload).displayAuthorName)
        payload.put("original_author", JSONObject().put("id", 99))
        assertEquals("原作者未填写", NovelJson.work(payload).displayAuthorName)
    }
}
