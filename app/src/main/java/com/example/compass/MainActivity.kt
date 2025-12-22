package com.example.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.compass.ui.theme.*
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var vibrator: Vibrator? = null

    // Core state
    private var azimuth by mutableFloatStateOf(0f)
    private var currentLocation by mutableStateOf<Location?>(null)
    private var isTracking by mutableStateOf(false)
    
    // Data
    private var waypoints = mutableStateListOf<Waypoint>()
    private var selectedWaypoint = mutableStateOf<Waypoint?>(null)
    private var trailHistory = mutableStateListOf<TrailPoint>()
    
    // UI Settings
    private var currentScreen by mutableIntStateOf(1) // Start on Compass
    private var isDarkMode by mutableStateOf(true)
    private var bearingLock by mutableStateOf(false)
    private var gpsAccuracy by mutableFloatStateOf(0f)

    private var hasLocationPermission = false
    private var hasAlertedForWaypoint = false

    companion object {
        const val GPS_INTERVAL = 2000L
        const val ALERT_DIST_METERS = 10f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // System services
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        hasLocationPermission = checkPermission()
        loadWaypoints()
        
        // Restore theme
        val prefs = getSharedPreferences("compass_prefs", Context.MODE_PRIVATE)
        isDarkMode = prefs.getBoolean("dark_mode", true)

        setContent {
            CompassApp()
        }
    }

    private fun checkPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @Composable
    fun CompassApp() {
        var showClearDialog by remember { mutableStateOf(false) }
        var showAddDialog by remember { mutableStateOf(false) }
        var showPermDialog by remember { mutableStateOf(false) }
        
        val colors = if (isDarkMode) DarkThemeColors else LightThemeColors
        
        // Handle permissions
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { perms ->
            val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            hasLocationPermission = granted
            if (granted) startGPS()
            else Toast.makeText(this, "Permission required for GPS", Toast.LENGTH_SHORT).show()
        }

        Scaffold(
            containerColor = colors.background,
            bottomBar = {
                BottomNav(
                    currentIndex = currentScreen,
                    isDark = isDarkMode,
                    onSelect = { currentScreen = it }
                )
            }
        ) { pad ->
            Box(modifier = Modifier.padding(pad)) {
                when (currentScreen) {
                    0 -> RadarScreen(
                        location = currentLocation,
                        history = trailHistory,
                        waypoints = waypoints,
                        azimuth = azimuth,
                        target = selectedWaypoint.value,
                        accuracy = gpsAccuracy,
                        isTracking = isTracking,
                        onSelect = { selectedWaypoint.value = it },
                        onAdd = { showAddDialog = true },
                        isDark = isDarkMode
                    )
                    1 -> MainCompassScreen(
                        onToggleGps = { 
                            if (hasLocationPermission) {
                                if (isTracking) stopGPS() else startGPS()
                            } else {
                                showPermDialog = true
                            }
                        },
                        onAddPoint = { showAddDialog = true }
                    )
                    2 -> SavedPointsScreen(
                        waypoints = waypoints,
                        location = currentLocation,
                        azimuth = azimuth,
                        selected = selectedWaypoint.value,
                        onBack = { currentScreen = 1 },
                        onClear = { showClearDialog = true },
                        onSelect = { 
                            selectedWaypoint.value = it
                            hasAlertedForWaypoint = false
                            currentScreen = 1 // jump back to compass
                        },
                        onAdd = { showAddDialog = true },
                        isDark = isDarkMode
                    )
                    3 -> SettingsView()
                }
            }
        }

        if (showPermDialog) {
            PermissionDialog(
                onDismiss = { showPermDialog = false },
                onConfirm = {
                    showPermDialog = false
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                },
                isDark = isDarkMode
            )
        }

        if (showClearDialog) {
            DeleteDialog(
                onDismiss = { showClearDialog = false },
                onConfirm = { 
                    waypoints.clear()
                    trailHistory.clear()
                    selectedWaypoint.value = null
                    saveWaypoints()
                },
                isDark = isDarkMode
            )
        }

        if (showAddDialog) {
            AddPointDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, cat, note ->
                    saveNewWaypoint(name, cat, note)
                    showAddDialog = false
                },
                isDark = isDarkMode
            )
        }
    }

    @Composable
    fun MainCompassScreen(onToggleGps: () -> Unit, onAddPoint: () -> Unit) {
        val theme = if (isDarkMode) DarkThemeColors else LightThemeColors
        
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Status Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = theme.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("GPS SIGNAL", color = theme.textSec, fontSize = 10.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SignalCellularAlt, null, 
                                tint = if (isTracking) SuccessGreen else ErrorRed, 
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isTracking) "Strong" else "Offline", color = theme.textPri, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { currentScreen = 3 }) {
                        Icon(Icons.Default.Settings, null, tint = theme.textPri)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Action Button
            Button(
                onClick = onToggleGps,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTracking) ErrorRed else SuccessGreen
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(if (isTracking) Icons.Default.GpsOff else Icons.Default.GpsFixed, null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(if (isTracking) "STOP TRACKING" else "START TRACKING")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Active Target Card
            selectedWaypoint.value?.let { target ->
                Surface(color = theme.card, shape = RoundedCornerShape(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(LimeAccent)) {
                            Icon(Icons.Default.Navigation, null, tint = Color.Black, modifier = Modifier.align(Alignment.Center))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TRACKING", color = theme.textSec, fontSize = 10.sp)
                            Text(target.name, color = theme.textPri, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { selectedWaypoint.value = null }) {
                            Icon(Icons.Default.Close, null, tint = theme.textSec)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // The Big Compass
            CompassView(
                azimuth = azimuth,
                waypoints = waypoints,
                userLoc = currentLocation,
                target = selectedWaypoint.value,
                onSelect = { 
                    selectedWaypoint.value = it
                    hasAlertedForWaypoint = false
                },
                locked = bearingLock,
                isDark = isDarkMode
            )

            // Distance Info
            Spacer(modifier = Modifier.height(16.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("DISTANCE", color = theme.textSec, fontSize = 12.sp, letterSpacing = 2.sp)
                
                val target = selectedWaypoint.value
                val curLoc = currentLocation
                
                if (target != null && curLoc != null) {
                    val dist = curLoc.distanceTo(target)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (dist >= 1000) String.format("%.1f", dist/1000) else dist.toInt().toString(),
                            fontSize = 56.sp, fontWeight = FontWeight.Bold, color = theme.textPri
                        )
                        Text(
                            if (dist >= 1000) "km" else "m",
                            fontSize = 24.sp, color = theme.textSec, modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                } else {
                    Text("--", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = theme.textSec.copy(0.3f))
                    Text(if (!isTracking) "GPS Off" else "No Target", color = theme.textSec)
                }
            }
        }
    }

    @Composable
    fun SettingsView() {
        val theme = if (isDarkMode) DarkThemeColors else LightThemeColors
        
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = theme.textPri)
            Spacer(modifier = Modifier.height(24.dp))

            // Quick helper for setting rows
            @Composable fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, content: @Composable () -> Unit) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = theme.textSec)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(title, color = theme.textPri, fontWeight = FontWeight.SemiBold)
                            Text(sub, color = theme.textSec, fontSize = 12.sp)
                        }
                    }
                    content()
                }
            }

            Surface(color = theme.card, shape = RoundedCornerShape(16.dp)) {
                Column {
                    SettingRow(if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode, "Dark Mode", "Toggle app theme") {
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { 
                                isDarkMode = it
                                getSharedPreferences("compass_prefs", Context.MODE_PRIVATE).edit().putBoolean("dark_mode", it).apply()
                            }
                        )
                    }
                    Divider(color = theme.surface)
                    SettingRow(Icons.Default.Lock, "Bearing Lock", "Keep north up") {
                        Switch(checked = bearingLock, onCheckedChange = { bearingLock = it })
                    }
                }
            }
        }
    }

    @Composable
    fun BottomNav(currentIndex: Int, isDark: Boolean, onSelect: (Int) -> Unit) {
        val navColor = if (isDark) DarkSurface else LightSurface
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(navColor)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val items = listOf(
                    Triple(0, "Radar", Icons.Default.Radar),
                    Triple(1, "Compass", Icons.Default.Explore),
                    Triple(2, "Saved", Icons.Default.Bookmark),
                    Triple(3, "Settings", Icons.Default.Settings)
                )
                
                items.forEach { (idx, label, icon) ->
                    val isSel = currentIndex == idx
                    IconButton(onClick = { onSelect(idx) }) {
                        Icon(icon, null, tint = if (isSel) (if(isDark) LimeAccent else BlueAccent) else Color.Gray)
                    }
                }
            }
        }
    }

    // Logic Helpers
    private fun startGPS() {
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL, 0f, this)
            sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_UI)
            isTracking = true
            Toast.makeText(this, "Tracking active", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            hasLocationPermission = false
        }
    }

    private fun stopGPS() {
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    private fun saveNewWaypoint(name: String, cat: WaypointCategory, notes: String) {
        currentLocation?.let { loc ->
            val finalName = name.ifBlank { "Point ${waypoints.size + 1}" }
            waypoints.add(Waypoint(name = finalName, latitude = loc.latitude, longitude = loc.longitude, category = cat, notes = notes))
            saveWaypoints()
        } ?: Toast.makeText(this, "Wait for GPS fix", Toast.LENGTH_SHORT).show()
    }

    private fun saveWaypoints() {
        // Simple CSV format
        val data = waypoints.joinToString("\n") { 
            "${it.id},${it.name},${it.latitude},${it.longitude},${it.category.name},${it.notes}" 
        }
        openFileOutput("waypoints.txt", Context.MODE_PRIVATE).write(data.toByteArray())
    }

    private fun loadWaypoints() {
        val file = File(filesDir, "waypoints.txt")
        if (!file.exists()) return
        
        waypoints.clear()
        file.readLines().forEach { line ->
            try {
                val p = line.split(",")
                if (p.size >= 4) {
                    val cat = try { WaypointCategory.valueOf(p[4]) } catch (e: Exception) { WaypointCategory.CUSTOM }
                    val notes = if (p.size >= 6) p[5] else ""
                    waypoints.add(Waypoint(p[0], p[1], p[2].toDouble(), p[3].toDouble(), cat, notes))
                }
            } catch (e: Exception) { /* skip bad lines */ }
        }
    }

    // Sensor impl
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val matrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(matrix, orientation)
            var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (deg < 0) deg += 360
            azimuth = deg
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // Location impl
    override fun onLocationChanged(loc: Location) {
        currentLocation = loc
        gpsAccuracy = loc.accuracy
        
        if (isTracking) {
            // Only add trail point if we moved > 2m
            val last = trailHistory.lastOrNull()
            if (last == null || loc.distanceTo(last) > 2f) {
                trailHistory.add(TrailPoint(loc.latitude, loc.longitude))
            }
        }
        
        // Alert logic
        selectedWaypoint.value?.let { wp ->
            if (loc.distanceTo(wp) < ALERT_DIST_METERS && !hasAlertedForWaypoint) {
                hasAlertedForWaypoint = true
                vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                Toast.makeText(this, "Arrived at ${wp.name}", Toast.LENGTH_LONG).show()
            }
        }
    }
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
    @Deprecated("Dep") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    
    // Extensions
    fun Location.distanceTo(wp: Waypoint): Float {
        val res = floatArrayOf(0f)
        Location.distanceBetween(this.latitude, this.longitude, wp.latitude, wp.longitude, res)
        return res[0]
    }
    fun Location.distanceTo(tp: TrailPoint): Float {
        val res = floatArrayOf(0f)
        Location.distanceBetween(this.latitude, this.longitude, tp.lat, tp.lon, res)
        return res[0]
    }
}

// Quick theme helpers
data class ThemeColors(val background: Color, val surface: Color, val card: Color, val textPri: Color, val textSec: Color)
val DarkThemeColors = ThemeColors(DarkBackground, DarkSurface, DarkCard, TextPrimary, TextSecondary)
val LightThemeColors = ThemeColors(LightBackground, LightSurface, LightCard, TextPrimaryLight, TextSecondaryLight)