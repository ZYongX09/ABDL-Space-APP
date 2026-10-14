package org.joinmastodon.android.model

import com.google.gson.Gson
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 31, 35], application = CompatibilityTestApplication::class)
class AdvertisementCompatibilityTest {
    private val gson = Gson()

    @Test
    fun parsesNullableAdvertisementWithLegacyAliases() {
        val status = gson.fromJson(
            """
            {
              "id":"status-1",
              "advertisement_data":{
                "ad_id":"ad-1",
                "ad_type":"official",
                "display_name":"官方活动",
                "targetUrl":"https://example.com/campaign"
              }
            }
            """.trimIndent(), Status::class.java
        )

        val advertisement = requireNotNull(status.advertisement)
        assertEquals("ad-1", advertisement.id)
        assertEquals("官方广告", advertisement.getLabel())
        assertEquals("https://example.com/campaign", advertisement.targetUrl)
    }

    @Test
    fun missingAdvertisementKeepsOrdinaryStatusCompatible() {
        val status = gson.fromJson("""{"id":"status-2","content":"ordinary"}""", Status::class.java)
        assertNull(status.advertisement)
    }

    @Test
    fun bodyFallsBackToContentFirstLine() {
        val advertisement = Advertisement()
        assertEquals("headline", advertisement.getBody("headline\nsecond line"))
        assertEquals("", advertisement.getBody(null))
    }

    @Test
    fun advertiserBodyIsNotTruncatedToFirstLine() {
        val advertisement = Advertisement()
        advertisement.description = "第一行\n第二行\n第三行"
        assertEquals("第一行\n第二行\n第三行", advertisement.getBody("ignored fallback"))
    }

    @Test
    fun merchantLabelIsDefault() {
        val advertisement = Advertisement()
        advertisement.type = "sponsor"
        assertEquals("商家广告", advertisement.getLabel())
    }
}
