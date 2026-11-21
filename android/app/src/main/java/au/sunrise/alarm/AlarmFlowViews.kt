package au.sunrise.alarm

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/** The night, alarm and morning screens (see AlarmFlowActivity for what they do). */
@Composable
fun AlarmFlowScreens(a: AlarmFlowActivity) {
    val theme = a.theme
    a.soundChanged   // re-read the sound's state when it changes
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (a.mode) {
            "night" -> NightView(a, theme)
            "ring", "snooze" -> RingView(a, theme)
            "morning" -> MorningView(a, theme)
        }
        if (a.now < a.volumeHudUntil) VolumeHud(a.sound.curVol)
    }
}

/** "6:30" (or "06:30" with the phone on 24-hour time). */
private fun clock(ms: Long, is24Hour: Boolean): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm"))

private fun timeWithAmPm(ms: Long, is24Hour: Boolean): String =
    HomeText.time(AlarmSettings.formatTime(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime()), is24Hour)

@Composable
private fun bigTextSize(maxWidth: Dp, fraction: Float, cap: Float) =
    with(LocalDensity.current) { min(maxWidth.value * fraction, cap).dp.toSp() }

// ---------- night ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NightView(a: AlarmFlowActivity, theme: FullTheme) {
    val p = a.progress(a.now)
    val f = Sunrise.frame(theme, a.look, p, a.nightLight)
    val clockColour = rgb(f.clock)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // The sky, with the sun's glow rising from below the screen (the page's radial gradient)
                drawRect(rgb(f.sky))
                val c = Offset(size.width * 0.5f, size.height * (f.sunY / 100).toFloat())
                val rx = size.width * (f.sunR / 100).toFloat()
                val ry = size.height * (f.sunR / 100).toFloat()
                if (rx > 0f && ry > 0f) scale(1f, ry / rx, pivot = c) {
                    drawCircle(Brush.radialGradient(listOf(rgb(f.glow), Color.Transparent), center = c, radius = rx), radius = rx, center = c)
                }
            }
            .pointerInput(Unit) { detectTapGestures { a.showControls() } },
        contentAlignment = Alignment.Center,
    ) {
        val look = a.look
        val clockSize = when (look.clockSize) {
            2 -> bigTextSize(maxWidth, 0.42f, 300f)
            1 -> bigTextSize(maxWidth, 0.36f, 240f)
            else -> bigTextSize(maxWidth, 0.30f, 190f)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(a.clockShift.first.dp, a.clockShift.second.dp),
        ) {
            Text(
                clock(a.now, a.is24Hour), color = clockColour, fontWeight = if (look.clockBold) FontWeight.Normal else FontWeight.ExtraLight,
                fontSize = clockSize, maxLines = 1,
            )
            if (look.nightDate) Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                color = clockColour.copy(alpha = 0.85f), fontSize = 20.sp,
            )
            Text(
                if (p > 0) "Rise and shine…" else "⏰ " + timeWithAmPm(a.alarmAt, a.is24Hour),
                color = clockColour.copy(alpha = 0.85f), fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (a.now < a.controlsUntil) {
            val src = a.soundSettings.src
            FlowRow(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (src != "chimes") {
                    val playing = a.sound.earlyPlay && a.sound.using != null
                    NightButton(if (playing) "■ Stop music" else when (src) {
                        "radio" -> "▶ Play radio"; "spotify" -> "▶ Play Spotify"
                        else -> "▶ Play " + a.soundSettings.appName.ifEmpty { "music" }
                    }) { a.toggleNightMusic(); a.showControls() }
                    if (playing && a.mode == "night" && a.now < a.alarmAt - a.soundSettings.earlyMin * 60_000L) {
                        NightButton(if (a.sleepUntil > 0) "⏲ Stops at " + timeWithAmPm(a.sleepUntil, a.is24Hour) else "⏲ Sleep timer off") {
                            a.cycleSleepTimer()
                        }
                    }
                }
                if (a.mode == "night" && a.progress(a.now) == 0.0) {
                    val name = NoisePlayer.NAMES.getValue(a.night.sleepSound)
                    NightButton(if (a.noise.playing) "■ Stop ${name.lowercase()}" else "🌧 $name") { a.toggleSleepSound() }
                    if (a.noise.playing) NightButton(if (a.sleepUntil > 0) "⏲ Stops at " + timeWithAmPm(a.sleepUntil, a.is24Hour) else "⏲ Sleep timer off") {
                        a.cycleSleepTimer()
                    }
                }
                NightButton("☀ I'm up") { a.upFromNight() }
                NightButton("💡 Night light") { a.chooseNightLight(1) }
                NightButton("Change alarm") { a.changeAlarm() }
                NightButton("Turn off alarm") { a.turnOff() }
            }
        }

        if (a.nightLight > 0) NightLight(a, theme)
    }
}

@Composable
private fun NightButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xD9281912), contentColor = Color(0xFFD9B89A)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, fontSize = 15.sp) }
}

/** A warm glow to see by in the night. Tap to turn off; double-tap for brighter. Off by itself after 10 minutes. */
@Composable
private fun NightLight(a: AlarmFlowActivity, theme: FullTheme) {
    val bright = a.nightLight == 2
    val c1 = rgb(theme.v(if (bright) "nb1" else "nl1"))
    val c2 = rgb(theme.v(if (bright) "nb2" else "nl2"))
    val c3 = rgb(theme.v(if (bright) "nb3" else "nl3"))
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(c3)
                val c = Offset(size.width * 0.5f, size.height * 0.55f)
                val rx = size.width * (if (bright) 0.85f else 0.75f)
                val ry = size.height * (if (bright) 0.70f else 0.60f)
                scale(1f, ry / rx, pivot = c) {
                    drawCircle(Brush.radialGradient(0f to c1, 0.45f to c2, 1f to c3, center = c, radius = rx), radius = rx, center = c)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { a.chooseNightLight(0) }, onDoubleTap = { a.chooseNightLight(if (a.nightLight == 1) 2 else 1) })
            }
            .semantics { contentDescription = "Night light. Tap to turn off, double-tap for brighter." },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            (if (a.nightLight == 1) "Double-tap for brighter · " else "") + "Tap to turn off\nTurns off by itself in 10 min",
            color = Color(0x8CFFDCBE), fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 24.sp,
            modifier = Modifier.padding(bottom = 40.dp),
        )
    }
}

// ---------- alarm (ringing and snoozing) ----------

@Composable
private fun RingView(a: AlarmFlowActivity, theme: FullTheme) {
    val r1 = rgb(theme.v("r1")); val r2 = rgb(theme.v("r2")); val r3 = rgb(theme.v("r3"))
    val snoozing = a.mode == "snooze"
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(r3)
            val c = Offset(size.width * 0.5f, size.height * 0.85f)
            val rx = size.width * 0.9f; val ry = size.height * 0.7f
            scale(1f, ry / rx, pivot = c) {
                drawCircle(Brush.radialGradient(0f to r1, 0.55f to r2, 1f to r3, center = c, radius = rx), radius = rx, center = c)
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val landscape = maxWidth > maxHeight
        val clockSize = if (landscape) bigTextSize(maxWidth, 0.22f, 160f) else bigTextSize(maxWidth, 0.28f, 180f)
        val info: @Composable () -> Unit = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock(a.now, a.is24Hour), color = rgb(theme.v("rInk")), fontWeight = FontWeight.ExtraLight, fontSize = clockSize, maxLines = 1)
                val left = ((a.snoozeUntil - a.now).coerceAtLeast(0) + 999) / 1000
                Text(
                    if (snoozing) "Snoozing · ${left / 60}:%02d".format(left % 60) else "Good morning",
                    color = rgb(theme.v("rSub")), fontSize = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                )
                Text(
                    a.status, color = rgb(theme.v("rSub2")), fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        val buttons: @Composable () -> Unit = {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(if (landscape) 0.4f else 0.86f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(
                    onClick = { a.snooze() }, shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.55f), contentColor = rgb(theme.v("rBtn"))),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("Snooze ${a.alarm.snooze} min", fontSize = 22.sp) }
                Button(
                    onClick = { a.enterMorning(true) }, shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rgb(theme.v("rBtn")), contentColor = rgb(theme.v("rBtnText"))),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                ) { Text("I'm up", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (landscape) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) { info(); buttons() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { info(); Spacer(Modifier.height(28.dp)); buttons() }
        }
    }
}

// ---------- morning ----------

@Composable
private fun MorningView(a: AlarmFlowActivity, theme: FullTheme) {
    val context = LocalContext.current
    val ink = rgb(theme.v("mInk"))
    val m = a.morning
    var weather by remember { mutableStateOf<Weather?>(null) }
    var weatherNote by remember { mutableStateOf("Loading…") }
    var tasks by remember { mutableStateOf<List<TodoTask>?>(null) }
    var tasksNote by remember { mutableStateOf("Loading…") }
    var events by remember { mutableStateOf<List<CalEvent>?>(null) }
    LaunchedEffect(a.morningRefresh) {
        val today = LocalDate.now()
        if (m.calendar) events = withContext(Dispatchers.IO) { runCatching { CalendarData.today(context) }.getOrDefault(emptyList()) }
        val json = withContext(Dispatchers.IO) { WeatherData.get(context, m.lat, m.lon, false) }
        weather = json?.let { runCatching { WeatherData.parse(it, today) }.getOrNull() }
        weatherNote = if (weather == null) "Couldn't load the weather. Check the Wi-Fi." else ""
        if (m.todoToken.isNotEmpty()) {
            when (val r = withContext(Dispatchers.IO) { Todoist.load(m.todoToken, today) }) {
                is Todoist.Result.Tasks -> { tasks = r.list; tasksNote = if (r.list.isEmpty()) "Nothing due today. Enjoy it." else "" }
                Todoist.Result.BadToken -> tasksNote = "Todoist didn't accept the token. Check it in settings."
                Todoist.Result.Unreachable -> tasksNote = "Couldn't reach Todoist right now."
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to rgb(theme.v("m1")), 0.55f to rgb(theme.v("m2")), 1f to rgb(theme.v("m3")))),
    ) {
        val landscape = maxWidth > maxHeight
        val morningClockSize = bigTextSize(if (landscape) maxWidth * 0.5f else maxWidth, 0.24f * (if (landscape) 1.4f else 1f), 150f)
        val left: @Composable ColumnScope.() -> Unit = {
            val hour = Instant.ofEpochMilli(a.now).atZone(ZoneId.systemDefault()).hour
            Text(if (hour < 12) "Good morning" else if (hour < 17) "Good afternoon" else "Good evening", color = rgb(theme.v("mGreet")), fontSize = 22.sp, fontWeight = FontWeight.Light)
            Text(clock(a.now, a.is24Hour), color = ink, fontWeight = FontWeight.ExtraLight, fontSize = morningClockSize, maxLines = 1)
            Text(
                LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                color = rgb(theme.v("mSub")), fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            val woke = remember(a.morningRefresh) {
                AlarmLog.entries(context).lastOrNull()?.takeIf { a.now - it.alarm in 0..12 * 3_600_000L }?.let { AlarmLog.line(it, a.is24Hour) } ?: ""
            }
            if (woke.isNotEmpty()) Text("⏰ $woke", color = rgb(theme.v("mSub")), fontSize = 15.sp, modifier = Modifier.padding(top = 0.dp, bottom = 16.dp))
            if (a.sound.musicOn) {
                Card(theme, "Now playing") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.status.ifEmpty { when (a.sound.using) { "radio" -> "📻 Radio"; "spotify" -> "🎵 Spotify"; else -> "🎵 " + a.soundSettings.appName.ifEmpty { "Music" } } },
                            color = ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        PillButton("−", theme, "Quieter") { a.volume(false) }
                        Spacer(Modifier.width(8.dp))
                        PillButton("+", theme, "Louder") { a.volume(true) }
                        Spacer(Modifier.width(8.dp))
                        PillButton("■ Stop", theme, null) { a.stopMusic() }
                    }
                }
            }
        }
        val right: @Composable ColumnScope.() -> Unit = {
            Card(theme, "Weather · ${m.place}") {
                val w = weather
                if (w == null) Text(weatherNote, color = rgb(theme.v("mMuted")), fontSize = 15.sp)
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(w.emoji, fontSize = 54.sp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("${w.temp}°", color = ink, fontSize = 52.sp, fontWeight = FontWeight.ExtraLight)
                            Text("${w.now} · feels ${w.feels}°", color = ink, fontSize = 16.sp)
                        }
                    }
                    Text("Today: ${w.todayText}" + if (w.rainMm >= 0.5) ", about ${"%.0f".format(w.rainMm)} mm of rain" else "",
                        color = ink, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                    val hourNow = Instant.ofEpochMilli(a.now).atZone(ZoneId.systemDefault()).hour
                    val summary = w.summary(hourNow)
                    if (summary.isNotEmpty()) Text(summary, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("High", "${w.high}°", theme); Stat("Low", "${w.low}°", theme)
                        Stat("Rain", "${w.rainChance}%", theme); Stat("Wind", "${w.wind} km/h", theme)
                    }
                    if (w.rainByHour.isNotEmpty()) RainByHour(w.rainByHour, a.is24Hour, theme)
                    if (w.uv >= 8) Text("☀️ UV ${w.uv}: slip, slop, slap", color = ink, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            Button(
                onClick = { openBom(context) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.7f), contentColor = rgb(theme.v("mBtn"))),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) { Text("Open BOM Weather ↗", fontSize = 16.sp) }

            Card(theme, "Daylight") {
                val sun = SunTimes.of(LocalDate.now(), m.lat, m.lon)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (sun == null) Stat("Sunrise", "–", theme) else Stat("Sunrise", timeWithAmPm(sun.first, a.is24Hour), theme)
                    val len = sun?.let { ((it.second - it.first) / 60_000).toInt() }
                    Stat("Daylight", len?.let { "${it / 60}h ${it % 60}m" } ?: "–", theme)
                    if (sun == null) Stat("Sunset", "–", theme) else Stat("Sunset", timeWithAmPm(sun.second, a.is24Hour), theme)
                }
            }

            if (m.calendar) {
                Card(theme, "Calendar") {
                    val list = events
                    when {
                        !CalendarData.allowed(context) -> Text("Allow Sunrise to see your calendar in Morning settings.", color = rgb(theme.v("mMuted")), fontSize = 15.sp)
                        list == null -> Text("Loading…", color = rgb(theme.v("mMuted")), fontSize = 15.sp)
                        list.isEmpty() -> Text("Nothing else in the calendar today.", color = rgb(theme.v("mMuted")), fontSize = 15.sp)
                        else -> list.forEach { e ->
                            Row(Modifier.padding(vertical = 6.dp)) {
                                Text(CalendarData.timeText(e, a.is24Hour), color = rgb(theme.v("mMuted")), fontSize = 16.sp, modifier = Modifier.width(84.dp))
                                Text(e.title, color = ink, fontSize = 17.sp)
                            }
                        }
                    }
                }
            }

            if (m.todoToken.isNotEmpty()) {
                Card(theme, "Today") {
                    tasks?.forEach { t ->
                        Row(Modifier.padding(vertical = 8.dp)) {
                            Box(Modifier.padding(top = 7.dp).size(10.dp).clip(CircleShape).background(
                                when (t.priority) { 4 -> Color(0xFFD1453B); 3 -> Color(0xFFEB8909); 2 -> Color(0xFF246FE0); else -> Color(0xFFC9A98C) }))
                            Spacer(Modifier.width(12.dp))
                            Text(t.content + if (t.overdue) "  (overdue)" else "", color = ink, fontSize = 17.sp)
                        }
                    }
                    if (tasksNote.isNotEmpty()) Text(tasksNote, color = rgb(theme.v("mMuted")), fontSize = 15.sp)
                }
            }
        }
        val done: @Composable () -> Unit = {
            Button(
                onClick = { a.done() }, shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = rgb(theme.v("mBtn")), contentColor = rgb(theme.v("rBtnText"))),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text("Done", fontSize = 20.sp) }
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(0.38f).verticalScroll(rememberScrollState())) { left(); done() }
                Column(Modifier.weight(0.62f).verticalScroll(rememberScrollState())) { right() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 30.dp)) {
                left(); right(); done()
            }
        }
    }
}

@Composable
private fun Card(theme: FullTheme, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.55f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(title.uppercase(), color = rgb(theme.v("mLabel")), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** The BOM Weather app (radar, warnings) if it's installed, otherwise bom.gov.au. */
private fun openBom(context: android.content.Context) {
    val i = context.packageManager.getLaunchIntentForPackage(MorningSettings.BOM_APP)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse(MorningSettings.BOM_SITE))
    runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The chance of rain through the day, as small bars: 6 am, 9 am … 9 pm. */
@Composable
private fun RainByHour(rain: List<Pair<Int, Int>>, is24Hour: Boolean, theme: FullTheme) {
    val ink = rgb(theme.v("mInk"))
    val muted = rgb(theme.v("mMuted"))
    Text("CHANCE OF RAIN", color = muted, fontSize = 11.sp, letterSpacing = 0.7.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        rain.forEach { (hour, pct) ->
            val label = if (is24Hour) "%02d".format(hour) else "${if (hour % 12 == 0) 12 else hour % 12}${if (hour < 12) "am" else "pm"}"
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $pct% chance of rain" },
            ) {
                Text("$pct%", color = ink, fontSize = 13.sp)
                Box(Modifier.height(36.dp).width(14.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().height((36 * pct.coerceIn(4, 100) / 100).dp)
                            .clip(RoundedCornerShape(4.dp)).background(ink.copy(alpha = 0.25f + 0.6f * pct / 100f)),
                    )
                }
                Text(label, color = muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, theme: FullTheme) {
    Column {
        Text(label.uppercase(), color = rgb(theme.v("mMuted")), fontSize = 11.sp, letterSpacing = 0.7.sp)
        Text(value, color = rgb(theme.v("mInk")), fontSize = 18.sp)
    }
}

@Composable
private fun PillButton(text: String, theme: FullTheme, description: String?, onClick: () -> Unit) {
    Button(
        onClick = onClick, shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.7f), contentColor = rgb(theme.v("mBtn"))),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = Modifier.height(40.dp).let { if (description != null) it.semantics { contentDescription = description } else it },
    ) { Text(text, fontSize = 16.sp) }
}

/** Shows the volume for a moment when you press the volume buttons. */
@Composable
private fun VolumeHud(v: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 40.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xCC000000)).padding(horizontal = 22.dp, vertical = 14.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Volume ${(v * 100).toInt()}%", color = Color.White, fontSize = 16.sp)
            Box(Modifier.padding(top = 8.dp).width(180.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF))) {
                Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(6.dp).background(Color.White))
            }
        }
    }
}
