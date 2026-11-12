package com.grabbly.downloader.util

import com.grabbly.downloader.data.DownloadOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualityTest {
    private fun video(res: Int) = DownloadOption("v$res", "video", "MP4", "${res}p")
    private val audio = DownloadOption("audio", "audio", "MP3", "Audio only")
    private val photo = DownloadOption("photo", "photo", "JPG", "1080 × 1350")
    private val options = listOf(video(1080), video(720), video(360), audio)

    private fun pick(pref: QualityPreference, list: List<DownloadOption> = options, canAsk: Boolean = true) =
        QualityPicker.pick(list, pref, canAsk)?.id

    @Test
    fun askLetsTheUserChooseUnlessThatIsntPossible() {
        assertNull(pick(QualityPreference.Ask))
        assertEquals("v1080", pick(QualityPreference.Ask, canAsk = false))
    }

    @Test
    fun picksTheClosestQualityAtOrBelowTheChoice() {
        assertEquals("v1080", pick(QualityPreference.Best))
        assertEquals("v1080", pick(QualityPreference.P1080))
        assertEquals("v720", pick(QualityPreference.P720))
        assertEquals("v360", pick(QualityPreference.P480))
        // Every quality is bigger than wanted: the smallest one
        assertEquals("v720", pick(QualityPreference.P480, listOf(video(1080), video(720), audio)))
        // No resolutions known: the single "best" option
        assertEquals("best", pick(QualityPreference.P720, listOf(DownloadOption("best", "video", "MP4", "Best quality"))))
    }

    @Test
    fun soundOnlyAndPhotos() {
        assertEquals("audio", pick(QualityPreference.Audio))
        assertEquals("v720", pick(QualityPreference.Audio, listOf(video(720)))) // no sound-only choice
        assertEquals("audio", pick(QualityPreference.Best, listOf(audio)))      // a sound-only post
        assertEquals("photo", pick(QualityPreference.P480, listOf(photo)))
        assertEquals("photo", pick(QualityPreference.Ask, listOf(photo), canAsk = false))
        assertNull(pick(QualityPreference.Best, emptyList()))
    }

    @Test
    fun settingsKeysRoundTrip() {
        QualityPreference.entries.forEach { assertEquals(it, QualityPreference.fromKey(it.key)) }
        assertEquals(QualityPreference.Ask, QualityPreference.fromKey(null))
        assertEquals(QualityPreference.Ask, QualityPreference.fromKey("something else"))
    }

    @Test
    fun findsHashtags() {
        assertEquals("#roadtrip #sunset", Captions.hashtags("Golden hour 🌅 #roadtrip #sunset #RoadTrip"))
        assertEquals("#café #2026", Captions.hashtags("Morning at the #café #2026"))
        assertNull(Captions.hashtags("No tags here, just a link https://x.com/a#frag and R&#8217;s"))
        assertNull(Captions.hashtags(null))
        assertNull(Captions.hashtags("   "))
    }
}
