package au.sunrise.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Composable
fun MorningScreen(initial: MorningSettings, onChange: (MorningSettings) -> Unit, onBack: () -> Unit) {
    var s by remember { mutableStateOf(initial) }
    fun update(n: MorningSettings) { s = n; onChange(n) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var showToken by remember { mutableStateOf(false) }

    fun locate() {
        status = "Finding where you are…"
        scope.launch {
            val loc = withTimeoutOrNull(20_000) { currentLocation(context) }
            if (loc == null) { status = "Couldn't get your location. Check Location is on, then try again."; return@launch }
            val lat = MorningSettings.round3(loc.latitude)
            val lon = MorningSettings.round3(loc.longitude)
            val name = withContext(Dispatchers.IO) { placeName(context, lat, lon) } ?: "Your location"
            update(s.copy(lat = lat, lon = lon, place = name))
            status = null
        }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else status = "Sunrise needs location permission to find your weather. You can keep the current place."
    }

    SettingsScaffold("Morning screen", onBack) {
        SectionTitle("Weather")
        ListItem(
            headlineContent = { Text(s.place) },
            supportingContent = { Text(status ?: "Weather and daylight times are for this place") },
        )
        Row(Modifier.padding(horizontal = 16.dp)) {
            FilledTonalButton(onClick = {
                if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                else askPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }) { Text("Use my location") }
        }
        Hint("Your location is saved only on this phone.")

        SectionTitle("Calendar")
        val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            update(s.copy(calendar = ok))
        }
        SwitchRow(
            title = "Show today's calendar",
            subtitle = "Today's events from your phone's calendar, on the morning screen. They stay on this phone.",
            checked = s.calendar,
            onCheckedChange = { on ->
                if (on && context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                    askCalendar.launch(Manifest.permission.READ_CALENDAR)
                else update(s.copy(calendar = on))
            },
        )

        SectionTitle("Todoist")
        OutlinedTextField(
            value = s.todoToken,
            onValueChange = { update(s.copy(todoToken = it.trim())) },
            label = { Text("Todoist API token (optional)") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Hint("Shows today's and overdue tasks on the morning screen. Find the token in Todoist → Settings → Integrations → Developer. It's stored only on this phone.")
    }
}

/** A recent location if there is one, otherwise a fresh one (coarse is plenty for the weather). */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(c: Context): Location? = withContext(Dispatchers.Main) {
    val lm = c.getSystemService(LocationManager::class.java) ?: return@withContext null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null && System.currentTimeMillis() - last.time < 6 * 60 * 60_000L) return@withContext last
    val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return@withContext last
    if (Build.VERSION.SDK_INT >= 30) {
        suspendCancellableCoroutine<Location?> { cont ->
            runCatching {
                lm.getCurrentLocation(provider, null, c.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc ?: last) }
            }.onFailure { if (cont.isActive) cont.resume(last) }
        }
    } else {
        suspendCancellableCoroutine<Location?> { cont ->
            // Android 8–10: every method must be implemented (they weren't optional before Android 11)
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) { if (cont.isActive) cont.resume(loc) }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(p: String?, status: Int, extras: Bundle?) {}
            }
            runCatching {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, null)
            }.onFailure { if (cont.isActive) cont.resume(last) }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
        }
    }
}

/** The town or suburb for a position, if Android can tell (needs the internet on most phones). */
private fun placeName(c: Context, lat: Double, lon: Double): String? = runCatching {
    if (!Geocoder.isPresent()) return null
    @Suppress("DEPRECATION")
    val a = Geocoder(c).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return null
    listOf(a.locality, a.subAdminArea, a.adminArea).firstOrNull { !it.isNullOrBlank() }
}.getOrNull()
