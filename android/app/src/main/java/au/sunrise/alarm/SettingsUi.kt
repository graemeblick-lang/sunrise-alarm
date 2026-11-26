package au.sunrise.alarm

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// ---------- theme ----------

/** An 0xRRGGBB colour as an opaque Compose colour. */
fun rgb(c: Int): Color = Color(0xFF000000.toInt() or (c and 0xFFFFFF))

/** Material 3 colours from the app's colour theme, using the same tones as the web screens. */
private fun schemeFor(theme: Themes.Theme): ColorScheme {
    val t = Themes.tones(theme)
    return darkColorScheme(
        primary = rgb(t.primary),
        onPrimary = rgb(t.onPrimary),
        primaryContainer = rgb(t.primaryContainer),
        onPrimaryContainer = rgb(t.onPrimaryContainer),
        secondary = rgb(Themes.mix(t.primary, 0xffffff, .3)),
        onSecondary = rgb(t.onPrimary),
        secondaryContainer = rgb(t.secondaryContainer),
        onSecondaryContainer = rgb(t.onSecondaryContainer),
        tertiary = rgb(Themes.mix(t.primary, 0xffffff, .3)),
        surface = rgb(t.surface),
        onSurface = rgb(t.onSurface),
        surfaceVariant = rgb(t.containerHighest),
        onSurfaceVariant = rgb(t.onSurfaceVariant),
        surfaceContainerLowest = rgb(Themes.mix(t.surface, 0x000000, .3)),
        surfaceContainerLow = rgb(Themes.mix(t.surface, t.container, .5)),
        surfaceContainer = rgb(t.container),
        surfaceContainerHigh = rgb(t.containerHigh),
        surfaceContainerHighest = rgb(t.containerHighest),
        background = rgb(t.surface),
        onBackground = rgb(t.onSurface),
        outline = rgb(t.outline),
        outlineVariant = rgb(t.outlineVariant),
    )
}

/** The app's colour theme ("phone" = your wallpaper colours, Android 12+). */
@Composable
fun SunriseTheme(themeKey: String, content: @Composable () -> Unit) {
    val scheme = if (themeKey == Themes.PHONE && Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current)
    else schemeFor(Themes.byKey(themeKey))
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------- layout ----------

/**
 * Short confirmations at the bottom of the screen (Material snackbars), with an optional action such as Undo.
 * Every action that changes something says so, so you're never left wondering whether a tap worked.
 */
class Snack(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    val state get() = host
    fun show(message: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val r = host.showSnackbar(message, action, withDismissAction = action == null,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

/** The snackbar for the current screens (none in tests, where messages are simply not shown). */
val LocalSnack = staticCompositionLocalOf<Snack?> { null }

/** Show a confirmation (and an optional action) at the bottom of the screen. */
@Composable
fun rememberSnack(): (String, String?, () -> Unit) -> Unit {
    val snack = LocalSnack.current
    return { msg, action, onAction -> snack?.show(msg, action, onAction) }
}

/** A settings screen: large collapsing title with Back (and optional actions), and a scrolling column of content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String, onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snack = LocalSnack.current
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { if (snack != null) SnackbarHost(snack.state) },
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A list row with a switch: the whole row toggles it, and TalkBack reads it as one switch. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** One of a few options, as Material segmented buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Choice(options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        options.forEachIndexed { i, v ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label(v)) }
        }
    }
}

/** A whole-row tap target. TalkBack reads the row's text, then "double-tap to <label>". */
fun Modifier.clickableRow(label: String, onClick: () -> Unit): Modifier =
    this.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)

/** Supporting text under a section. */
@Composable
fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A row that opens something, showing what it's set to (the Material "preference" pattern):
 * an optional emoji icon, the title, its current value underneath, and a chevron.
 */
@Composable
fun ValueRow(title: String, value: String?, icon: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        leadingContent = icon?.let { { Text(it, style = MaterialTheme.typography.titleLarge) } },
        trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickableRow("open $title", onClick),
    )
}

/** A plain label above a control (segmented buttons, slider) inside a section. */
@Composable
fun ControlLabel(text: String, supporting: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
