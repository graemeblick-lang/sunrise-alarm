package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundSettingsTest {
    private val two = listOf(MyStation("Gold 104.3", "https://gold.example/stream"), MyStation("Smooth", "http://smooth.example/s"))

    @Test fun readsThePagesJson() {
        val s = SoundSettings.from(JSONObject("""{"src":"radio","station":7,"myStations":[{"name":"Gold","url":"https://g"},{"name":"Bad","url":""}],
            "songs":[{"name":"a.mp3","uri":"content://x/1"},{"name":"b","uri":"file:///nope"}],"vol":200,"spShuffle":false,"theme":"phone"}"""))
        assertEquals("radio", s.src); assertEquals(1, s.myStations.size); assertEquals(1, s.songs.size)
        assertEquals(100, s.vol); assertEquals(false, s.spShuffle)
        assertEquals(7, s.station)   // 6 built-in + 1 of yours, so 7 is "Custom stream…"
        assertEquals(0, SoundSettings.from(JSONObject("""{"station":9}""")).station)   // doesn't exist → first
    }

    @Test fun customIsAfterYourStations() {
        val s = SoundSettings(myStations = two)
        assertEquals(8, s.customIndex)
        assertEquals("Custom stream…", s.stationNames()[8])
        assertEquals(listOf("https://cust"), s.copy(station = 8, customUrl = "https://cust").stationUrls())
        assertEquals(emptyList<String>(), s.copy(station = 8, customUrl = "ftp://x").stationUrls())
        assertEquals(listOf("https://gold.example/stream"), s.copy(station = 6).stationUrls())
        assertEquals(3, s.copy(station = 0).stationUrls().size)
    }

    @Test fun unknownSourceFallsBackToChimes() = assertEquals("chimes", SoundSettings.from(JSONObject("""{"src":"tape"}""")).src)

    @Test fun removingAStationKeepsTheChoice() {
        val s = SoundSettings(myStations = two, station = 7)          // Smooth chosen
        assertEquals(6, s.removeStation(0).station)                     // Gold removed: Smooth moves up
        assertEquals(0, s.removeStation(1).station)                     // Smooth removed: back to the first
        assertEquals(2, s.copy(station = 2).removeStation(0).station)   // built-in choice unaffected
    }

    @Test fun addingAStationChoosesIt() {
        val s = SoundSettings().addStation(MyStation("Gold", "https://g"))
        assertEquals("radio", s.src); assertEquals(6, s.station)
        assertEquals(s, s.addStation(MyStation("Gold again", "https://g")))   // no duplicates
    }

    @Test fun writesWithoutTouchingOtherKeys() {
        val j = JSONObject("""{"time":"06:30","theme":"ocean"}""")
        SoundSettings(src = "songs", songs = listOf(Song("x", "content://a"))).into(j)
        assertEquals("06:30", j.getString("time")); assertEquals("songs", j.getString("src"))
        assertEquals("content://a", j.getJSONArray("songs").getJSONObject(0).getString("uri"))
        assertEquals(SoundSettings.from(j).songs, listOf(Song("x", "content://a")))
    }

    @Test fun parsesSpotifyLinks() {
        assertEquals("spotify:playlist:37i9dQZF1DX0SM0LYsmbMT" to "playlist",
            SoundSettings.parseSpotify("https://open.spotify.com/playlist/37i9dQZF1DX0SM0LYsmbMT?si=abc"))
        assertEquals("song", SoundSettings.parseSpotify("spotify:track:4uLU6hMCjMI75M1A2tKUQC")?.second)
        assertNull(SoundSettings.parseSpotify("https://example.com"))
    }
}
