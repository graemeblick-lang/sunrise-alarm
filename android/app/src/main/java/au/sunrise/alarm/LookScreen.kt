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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

private val SUN_COLOUR_NAMES = listOf("Dawn", "Glow", "Daylight")

@Composable
fun LookScreen(initial: LookSettings, onChange: (LookSettings) -> Unit, onPreview: () -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: LookSettings) { s = n; onChange(n) }
    var editingColour by remember { mutableStateOf<Int?>(null) }

    SettingsScaffold("Look & brightness", onBack) {
        SectionTitle("Colour theme")
        Column(Modifier.selectableGroup()) {
            val themes = buildList<Triple<String, String, String?>> {
                if (Build.VERSION.SDK_INT >= 31) add(Triple(Themes.PHONE, "Phone colours", "Matches your wallpaper"))
                Themes.ALL.forEach { add(Triple(it.key, it.name, null)) }
            }
            themes.forEach { (key, name, sub) ->
                val selected = s.theme == key || (key == "sunrise" && Themes.ALL.none { it.key == s.theme } && s.theme != Themes.PHONE)
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = sub?.let { { Text(it) } },
                    leadingContent = { ThemeSwatch(key) },
                    trailingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { update(s.copy(theme = key)) }),
                )
            }
        }

        SectionTitle("Sunrise colours")
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
            FilledTonalButton(onClick = onPreview) { Text("Preview the sunrise (1 minute)") }
        }

        SectionTitle("Overnight clock brightness")
        LabelledSlider(
            value = s.dim.toFloat(), range = 1f..6f, steps = 4,
            valueText = "${s.dim} of 6", description = "Overnight clock brightness",
        ) { update(s.copy(dim = it.toInt())) }

        SectionTitle("Sunrise brightness")
        LabelledSlider(
            value = s.sunMax.toFloat(), range = 15f..100f, steps = 0,
            valueText = "${s.sunMax}%", description = "Sunrise brightness",
        ) { update(s.copy(sunMax = it.toInt())) }
        Hint("How bright the screen gets by alarm time. It stays dim for most of the sunrise and brightens towards the end.")
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
