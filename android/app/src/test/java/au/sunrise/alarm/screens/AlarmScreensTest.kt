package au.sunrise.alarm.screens

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import au.sunrise.alarm.AlarmFlowActivity
import au.sunrise.alarm.SettingsStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real night, alarm and morning screens (AlarmFlowActivity), opened the way the app opens them:
 * Preview from the home screen, and an alarm whose time has come.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun radio() {
        // The radio only asks the playback service to start, which is safe in a test
        SettingsStore.write(ctx, """{"src":"radio","station":0,"snooze":9}""")
    }

    private fun open(i: Intent) = ActivityScenario.launch<AlarmFlowActivity>(i)

    @Test fun previewShowsTheNightClockThenMorning() {
        open(Intent(ctx, AlarmFlowActivity::class.java).putExtra("start", "preview")).use { s ->
            rule.onNodeWithText("⏰", substring = true).assertExists()
            s.onActivity { it.showControls() }        // a tap on the night screen shows the buttons
            rule.onNodeWithText("Turn off alarm").assertExists()
            rule.onNodeWithText("☀ I'm up").performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithText("Done").assertExists()
            rule.onNodeWithText("WEATHER ·", substring = true).assertExists()
        }
    }

    @Test fun anAlarmThatIsDueRingsAndSnoozes() {
        val due = System.currentTimeMillis() - 5_000
        open(Intent(ctx, AlarmFlowActivity::class.java).putExtra("wake", true).putExtra("alarmMs", due)).use {
            rule.onNodeWithText("Good morning").assertExists()
            rule.onNodeWithText("Snooze 9 min").performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithText("Snoozing", substring = true).assertExists()
            rule.onNodeWithText("I'm up").performSemanticsAction(SemanticsActions.OnClick)
            rule.onNodeWithText("Done").assertExists()
        }
    }
}
