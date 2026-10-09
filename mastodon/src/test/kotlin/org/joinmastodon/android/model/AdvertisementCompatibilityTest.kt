package org.joinmastodon.android.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        assertEquals("ad-1", status.advertisement.id)
        assertEquals("官方广告", status.advertisement.getLabel())
        assertEquals("https://example.com/campaign", status.advertisement.targetUrl)
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
    fun merchantLabelIsDefault() {
        val advertisement = Advertisement()
        advertisement.type = "sponsor"
        assertEquals("商家广告", advertisement.getLabel())
    }
}
