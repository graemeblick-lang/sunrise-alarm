package au.sunrise.alarm.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import au.sunrise.alarm.AlarmScreen
import au.sunrise.alarm.AlarmSettings
import au.sunrise.alarm.HomeScreen
import au.sunrise.alarm.LookSettings
import au.sunrise.alarm.MorningScreen
import au.sunrise.alarm.MorningSettings
import au.sunrise.alarm.NightScreen
import au.sunrise.alarm.NightSettings
import au.sunrise.alarm.SunriseScreen
import au.sunrise.alarm.SunriseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings screens, drawn and tapped on the computer (Robolectric): each shows its sections, and a tap
 * changes the right setting. Catches screens that crash or lose a control. Taps go through the controls'
 * click actions (what TalkBack uses), so they don't depend on the test screen's size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    @Test fun homeHasTheAlarmCardQuickAlarmAndSettingsInOrder() {
        var saved: AlarmSettings? = null
        rule.setContent {
            SunriseTheme("sunrise") {
                HomeScreen(refresh = 0, is24Hour = false, save = { saved = it }, onOpen = {}, onStart = {})
            }
        }
        rule.onNodeWithText("Skip next").assertIsDisplayed()
        rule.onNodeWithText("Quick alarm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Night").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Through the night").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Wake-up sound").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Morning").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Test the backup alarm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Back up").performScrollTo().assertIsDisplayed()

        val before = System.currentTimeMillis()
        rule.onNodeWithText("5 min").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        val once = saved!!.once
        assertTrue("one-off alarm about 5 minutes away", once in before + 4 * 60_000..before + 6 * 60_000)
    }

    @Test fun nightScreenSetsTheChargerHour() {
        var night: NightSettings? = null
        rule.setContent {
            SunriseTheme("sunrise") { NightScreen(LookSettings(), NightSettings(), onChange = {}, onNightChange = { night = it }, onBack = {}) }
        }
        rule.onNodeWithText("Start the night clock by itself").assertIsDisplayed()
        rule.onNodeWithText("9 pm").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(21, night!!.autoNight)
        rule.onNodeWithText("Sleep timer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Bedtime reminder").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Colour theme").performScrollTo().assertIsDisplayed()
    }

    @Test fun sunriseScreenSetsTheLength() {
        var dur = 0
        rule.setContent {
            SunriseTheme("sunrise") { SunriseScreen(LookSettings(), 30, onChange = {}, onDur = { dur = it }, onPreview = {}, onBack = {}) }
        }
        rule.onNodeWithText("45 min").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(45, dur)
        rule.onNodeWithText("Preview the sunrise (1 minute)").performScrollTo().assertIsDisplayed()
    }

    @Test fun scheduleScreenSetsTheHolidayState() {
        var saved: AlarmSettings? = null
        rule.setContent {
            SunriseTheme("sunrise") { AlarmScreen(AlarmSettings(), is24Hour = false, onChange = { saved = it }, onBack = {}) }
        }
        rule.onNodeWithText("Public holidays").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("VIC").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("VIC", saved!!.holidayState)
    }

    @Test fun morningScreenHasTheCalendarSwitch() {
        rule.setContent {
            SunriseTheme("sunrise") { MorningScreen(MorningSettings(), onChange = {}, onBack = {}) }
        }
        rule.onNodeWithText("Show today's calendar").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Use my location").performScrollTo().assertIsDisplayed()
    }
}
