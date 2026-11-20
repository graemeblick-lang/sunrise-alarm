package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioBrowserTest {
    @Test fun keepsPlayableUniqueStationsWithInfo() {
        val json = """[
          {"name":"Gold 104.3","url_resolved":"https://gold.example/s","codec":"AAC","bitrate":64,"state":"Victoria","tags":"80s,hits,pop","stationuuid":"u1"},
          {"name":"gold 104.3 ","url":"https://dup.example/s"},
          {"name":"No stream","url":""},
          {"name":"Odd","url":"rtsp://odd"},
          {"name":"Plain","url":"http://plain.example/s","country":"Australia"}
        ]"""
        val r = RadioBrowser.parse(json)
        assertEquals(listOf("Gold 104.3", "Plain"), r.map { it.name })
        assertEquals("Victoria · AAC 64k · 80s, hits", r[0].info)
        assertEquals("https://gold.example/s", r[0].url)
        assertEquals("Australia", r[1].info)
        assertEquals(MyStation("Gold 104.3", "https://gold.example/s", "", "u1"), r[0].toMine())
    }

    @Test fun atMostTwentyFive() {
        val json = (1..40).joinToString(",", "[", "]") { """{"name":"S$it","url":"https://x/$it"}""" }
        assertEquals(25, RadioBrowser.parse(json).size)
    }
}
