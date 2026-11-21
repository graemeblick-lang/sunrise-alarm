package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class PreflightTest {
    @Test fun allGood() = assertEquals(emptyList<String>(), Preflight.messages(Preflight.Found(src = "spotify")))

    @Test fun spotifyProblems() {
        assertEquals(listOf("Spotify isn't installed, so you'd wake to chimes."),
            Preflight.messages(Preflight.Found(src = "spotify", spotifyInstalled = false)))
        assertEquals(listOf("Spotify is logged out, so you'd wake to chimes. Open Spotify and log in."),
            Preflight.messages(Preflight.Found(src = "spotify", spotifyError = "NotLoggedInException")))
        assertEquals(listOf("Spotify isn't connected, so it will play what you last played (or chimes)."),
            Preflight.messages(Preflight.Found(src = "spotify", spotifyConnected = false)))
    }

    @Test fun radioAppAndPhone() {
        assertEquals(listOf("The radio station isn't answering, so you may wake to chimes. Check the Wi-Fi."),
            Preflight.messages(Preflight.Found(src = "radio", radioOk = false)))
        assertEquals(listOf("Podcast Addict isn't installed, so you'd wake to chimes."),
            Preflight.messages(Preflight.Found(src = "apps", appInstalled = false, appName = "Podcast Addict")))
        assertEquals(listOf("The phone's alarm volume is at zero.", "The battery is at 12% and not charging. Plug the phone in."),
            Preflight.messages(Preflight.Found(src = "chimes", alarmVolume = 0, battery = 12, charging = false)))
        // Media volume only matters for music apps and Spotify
        assertEquals(emptyList<String>(), Preflight.messages(Preflight.Found(src = "radio", radioOk = true, mediaVolume = 0)))
        assertEquals(listOf("The phone's media volume is at zero."), Preflight.messages(Preflight.Found(src = "spotify", mediaVolume = 0)))
    }
}
