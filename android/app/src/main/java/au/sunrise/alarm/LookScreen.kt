package au.sunrise.alarm

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private val SUN_COLOUR_NAMES = listOf("Dawn", "Glow", "Daylight")

/** Settings → Night: what happens at bedtime (starting by itself, the reminder, falling asleep). */
@Composable
fun NightScreen(initialNight: NightSettings, onNightChange: (NightSettings) -> Unit, onBack: () -> Unit) {
    var n by remember { mutableStateOf(initialNight) }
    fun updateNight(v: NightSettings) { n = v; onNightChange(v) }

    SettingsScaffold("Night", onBack) {
        SwitchRow("Start on the charger", "The night clock starts by itself when the phone is plugged in after the time below (once a night)",
            n.autoNight > 0) { on -> updateNight(n.copy(autoNight = if (on) 21 else 0)) }
        if (n.autoNight > 0) {
            Choice(NightSettings.AUTO_NIGHT_HOURS.filter { it > 0 }, n.autoNight, { "${it - 12} pm" }) { updateNight(n.copy(autoNight = it)) }
        }
        SwitchRow("Bedtime reminder", if (n.bedtime > 0) "A notification ${n.bedtime} hours before your alarm, with a Goodnight button" else "A notification before your alarm",
            n.bedtime > 0) { on -> updateNight(n.copy(bedtime = if (on) 8 else 0)) }
        if (n.bedtime > 0) {
            Choice(NightSettings.BEDTIME_HOURS.filter { it > 0 }, n.bedtime, { "$it h before" }) { updateNight(n.copy(bedtime = it)) }
        }

        SectionTitle("Falling asleep")
        ControlLabel("Sleep sound", "Made by the app, no internet needed. Tap the night screen to play it.")
        Choice(NoisePlayer.KINDS.indices.toList(), NoisePlayer.KINDS.indexOf(n.sleepSound).coerceAtLeast(0),
            { NoisePlayer.NAMES.getValue(NoisePlayer.KINDS[it]) }) { updateNight(n.copy(sleepSound = NoisePlayer.KINDS[it])) }
        ControlLabel("Sleep timer", "Music and sleep sounds fade out after this long")
        Choice(NightSettings.SLEEP_TIMERS, n.sleepTimer, { if (it == 0) "Off" else "$it min" }) { updateNight(n.copy(sleepTimer = it)) }
        Spacer(Modifier.height(16.dp))
    }
}

/** Settings → Look: the colour theme, the night clock and the sunrise's brightness and colours. */
@Composable
fun LookScreen(initial: LookSettings, onChange: (LookSettings) -> Unit, onOpen: (String) -> Unit, onPreview: () -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: LookSettings) { s = n; onChange(n) }
    var pickingTheme by remember { mutableStateOf(false) }

    SettingsScaffold("Look", onBack) {
        ValueRow("Colour theme", themeName(s.theme)) { pickingTheme = true }

        SectionTitle("Night clock")
        Choice(listOf(0, 1, 2), s.clockSize, { listOf("Normal", "Large", "Huge")[it] }) { update(s.copy(clockSize = it)) }
        SwitchRow("Bolder numbers", "Easier to read without glasses", s.clockBold) { update(s.copy(clockBold = it)) }
        SwitchRow("Show the date", null, s.nightDate) { update(s.copy(nightDate = it)) }
        LabelledSlider(
            value = s.dim.toFloat(), range = 1f..6f, steps = 4,
            valueText = "Brightness overnight: ${s.dim} of 6", description = "Overnight clock brightness",
        ) { update(s.copy(dim = it.toInt())) }

        SectionTitle("Sunrise")
        LabelledSlider(
            value = s.sunMax.toFloat(), range = 15f..100f, steps = 0,
            valueText = "How bright it gets: ${s.sunMax}%", description = "Sunrise brightness",
        ) { update(s.copy(sunMax = it.toInt())) }
        Hint("It stays dim for most of the sunrise and brightens towards the end.")
        ValueRow("Colours", if (s.sunCustom) "Your own" else "The theme's sunrise") { onOpen("colours") }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilledTonalButton(onClick = onPreview) { Text("▶ Preview the sunrise") }
        }
    }

    if (pickingTheme) {
        AlertDialog(
            onDismissRequest = { pickingTheme = false },
            title = { Text("Colour theme") },
            text = {
                Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                    val themes = buildList<Pair<String, String>> {
                        if (Build.VERSION.SDK_INT >= 31) add(Themes.PHONE to "Phone colours (your wallpaper)")
                        Themes.ALL.forEach { add(it.key to it.name) }
                    }
                    themes.forEach { (key, name) ->
                        val selected = s.theme == key || (key == "sunrise" && Themes.ALL.none { it.key == s.theme } && s.theme != Themes.PHONE)
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) {
                                update(s.copy(theme = key)); pickingTheme = false
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ThemeSwatch(key)
                            Spacer(Modifier.width(14.dp))
                            Text(name, modifier = Modifier.weight(1f))
                            RadioButton(selected = selected, onClick = null)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingTheme = false }) { Text("Close") } },
        )
    }
}

internal fun themeName(key: String) = if (key == Themes.PHONE) "Phone colours" else Themes.byKey(key).name

/** Settings → Look → Colours: the sunrise's own colours (or the theme's). */
@Composable
fun ColoursScreen(initial: LookSettings, onChange: (LookSettings) -> Unit, onPreview: () -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: LookSettings) { s = n; onChange(n) }
    var editingColour by remember { mutableStateOf<Int?>(null) }

    SettingsScaffold("Sunrise colours", onBack) {
        SwitchRow(
            title = "Choose my own",
            subtitle = if (s.sunCustom) null else "Using the colour theme's sunrise",
            checked = s.sunCustom,
            onCheckedChange = { update(s.copy(sunCustom = it)) },
        )
        if (s.sunCustom) {
            val cols = s.sunCols.map { Themes.parse(it) ?: 0 }
            // What the screen goes through from night to alarm time
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(*Themes.customSky(cols[0], cols[1], cols[2]).map { (p, c) -> p to rgb(c) }.toTypedArray()))
                    .semantics { contentDescription = "Sunrise preview, from night to daylight" },
            )
            SUN_COLOUR_NAMES.forEachIndexed { i, name ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { ColourDot(cols[i]) },
                    modifier = Modifier.clickableRow("change the $name colour") { editingColour = i },
                )
            }
            SectionTitle("Ready-made")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Themes.SUN_PRESETS.forEach { (name, presetCols) ->
                    AssistChip(
                        onClick = { update(s.copy(sunCols = presetCols)) },
                        label = { Text(name) },
                        leadingIcon = { ColourDot(Themes.parse(presetCols[1]) ?: 0, size = 18) },
                    )
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FilledTonalButton(onClick = onPreview) { Text("▶ Preview the sunrise") }
        }
    }

    editingColour?.let { i ->
        ColourDialog(
            title = SUN_COLOUR_NAMES[i],
            initial = Themes.parse(s.sunCols[i]) ?: 0,
            onDismiss = { editingColour = null },
            onConfirm = { c ->
                update(s.copy(sunCols = s.sunCols.mapIndexed { j, old -> if (j == i) Themes.hex(c) else old }))
                editingColour = null
            },
        )
    }
}

@Composable
private fun ThemeSwatch(key: String) {
    val brush = if (key == Themes.PHONE) {
        Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer))
    } else {
        val t = Themes.byKey(key)
        Brush.radialGradient(listOf(rgb(t.glow), rgb(t.swatch), rgb(t.sky)))
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(brush))
}

@Composable
private fun ColourDot(c: Int, size: Int = 32) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(rgb(c))
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** A slider that saves when you let go, and tells TalkBack its value in words. */
@Composable
private fun LabelledSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    valueText: String, description: String, onDone: (Float) -> Unit,
) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(valueText, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onDone(v) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = description; stateDescription = valueText },
        )
    }
}

/** Pick a colour with hue, colour strength and brightness sliders. */
@Composable
private fun ColourDialog(title: String, initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val colour = android.graphics.Color.HSVToColor(floatArrayOf(h, sat, value)) and 0xFFFFFF
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(rgb(colour))
                        .semantics { contentDescription = "Chosen colour" },
                )
                Spacer(Modifier.height(16.dp))
                Text("Colour")
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..359f,
                    modifier = Modifier.semantics { contentDescription = "Hue" })
                Text("Strength")
                Slider(value = sat, onValueChange = { sat = it },
                    modifier = Modifier.semantics { contentDescription = "Colour strength" })
                Text("Brightness")
                Slider(value = value, onValueChange = { value = it },
                    modifier = Modifier.semantics { contentDescription = "Brightness" })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(colour) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
