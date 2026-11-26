package au.sunrise.alarm.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import au.sunrise.alarm.AlarmScreen
import au.sunrise.alarm.AlarmSettings
import au.sunrise.alarm.ChecksScreen
import au.sunrise.alarm.HomeScreen
import au.sunrise.alarm.LookScreen
import au.sunrise.alarm.LookSettings
import au.sunrise.alarm.MorningScreen
import au.sunrise.alarm.MorningSettings
import au.sunrise.alarm.NightScreen
import au.sunrise.alarm.NightSettings
import au.sunrise.alarm.SettingsHomeScreen
import au.sunrise.alarm.SunriseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home and settings screens, drawn and tapped on the computer (Robolectric): each shows its sections, and a tap
 * changes the right setting. Catches screens that crash or lose a control. Taps go through the controls'
 * click actions (what TalkBack uses), so they don't depend on the test screen's size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule val rule = createComposeRule()
    private val click = SemanticsActions.OnClick

    @Test fun homeIsOnlyTonightsAlarm() {
        var saved: AlarmSettings? = null
        val opened = ArrayList<String>()
        rule.setContent {
            SunriseTheme("sunrise") {
                HomeScreen(refresh = 0, is24Hour = false, save = { saved = it }, onOpen = { opened += it }, onStart = {})
            }
        }
        rule.onNodeWithText("tonight", substring = true).assertExists()          // the status line
        rule.onNodeWithText("Skip next").assertIsDisplayed()
        rule.onNodeWithText("Edit ›").performSemanticsAction(click)
        rule.onNodeWithText("Goodnight").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Preview the sunrise").performScrollTo().assertIsDisplayed()

        val before = System.currentTimeMillis()
        rule.onNodeWithText("5 min").performScrollTo().performSemanticsAction(click)
        assertTrue("one-off alarm about 5 minutes away", saved!!.once in before + 4 * 60_000..before + 6 * 60_000)
        assertEquals(listOf("alarm"), opened)
    }

    @Test fun alarmScreenHasEverythingAboutTheAlarm() {
        var saved: AlarmSettings? = null
        val opened = ArrayList<String>()
        rule.setContent {
            SunriseTheme("sunrise") {
                AlarmScreen(AlarmSettings(), soundSummary = "Chimes", is24Hour = false, onChange = { saved = it }, onOpen = { opened += it }, onBack = {})
            }
        }
        rule.onNodeWithText("When").assertExists()
        rule.onNodeWithText("Waking up").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sound").performScrollTo().performSemanticsAction(click)
        assertEquals(listOf("sound"), opened)
        rule.onNodeWithText("+2").performScrollTo().performSemanticsAction(click)
        assertEquals(2, saved!!.extra)
        rule.onNodeWithText("Public holidays").performScrollTo().performSemanticsAction(click)
        rule.onNodeWithText("Skip VIC holidays").performScrollTo().performSemanticsAction(click)
        assertEquals("VIC", saved!!.holidayState)
    }

    @Test fun settingsListsTheGroups() {
        val opened = ArrayList<String>()
        rule.setContent { SunriseTheme("sunrise") { SettingsHomeScreen(0, onOpen = { opened += it }, onBack = {}) } }
        listOf("Night", "Look", "Morning", "Phone check & rehearsal", "Back up & restore", "About").forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Look").performSemanticsAction(click)
        assertEquals(listOf("look"), opened)
    }

    @Test fun nightScreenTurnsOnTheChargerStart() {
        var night: NightSettings? = null
        rule.setContent { SunriseTheme("sunrise") { NightScreen(NightSettings(), onNightChange = { night = it }, onBack = {}) } }
        rule.onNodeWithText("Start on the charger").performSemanticsAction(click)
        assertEquals(21, night!!.autoNight)
        rule.onNodeWithText("Sleep sound").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Sleep timer").performScrollTo().assertIsDisplayed()
    }

    @Test fun lookScreenSetsTheClockSize() {
        var look: LookSettings? = null
        rule.setContent { SunriseTheme("sunrise") { LookScreen(LookSettings(), onChange = { look = it }, onOpen = {}, onPreview = {}, onBack = {}) } }
        rule.onNodeWithText("Colour theme").assertIsDisplayed()
        rule.onNodeWithText("Huge").performScrollTo().performSemanticsAction(click)
        assertEquals(2, look!!.clockSize)
        rule.onNodeWithText("▶ Preview the sunrise").performScrollTo().assertIsDisplayed()
    }

    @Test fun phoneCheckHasTheTests() {
        rule.setContent { SunriseTheme("sunrise") { ChecksScreen(0, false, onRehearse = {}, onBack = {}) } }
        rule.onNodeWithText("Rehearse the alarm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Test the backup alarm").performScrollTo().assertIsDisplayed()
    }

    @Test fun morningScreenHasTheCalendarSwitch() {
        rule.setContent { SunriseTheme("sunrise") { MorningScreen(MorningSettings(), onChange = {}, onBack = {}) } }
        rule.onNodeWithText("Show today's calendar").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Use my location").performScrollTo().assertIsDisplayed()
    }
}
