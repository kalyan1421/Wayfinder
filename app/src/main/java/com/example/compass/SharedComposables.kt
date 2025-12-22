package com.example.compass

import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.compass.ui.theme.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Main Compass UI drawn on Canvas
 */
@Composable
fun CompassView(
    azimuth: Float,
    waypoints: List<Waypoint>,
    userLoc: Location?,
    target: Waypoint?,
    onSelect: (Waypoint) -> Unit,
    locked: Boolean = false,
    isDark: Boolean = true
) {
    val textMeasurer = rememberTextMeasurer()
    var zoomScale by remember { mutableFloatStateOf(500f) } // meters radius
    
    val colors = if (isDark) DarkThemeColors else LightThemeColors
    val ringColor = if (isDark) CompassRingDark else Color.LightGray.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(16.dp)
            .shadow(if (isDark) 0.dp else 12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.card)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        zoomScale *= (1 / zoom)
                        zoomScale = zoomScale.coerceIn(200f, 2000f)
                    }
                }
                .pointerInput(waypoints) {
                    detectTapGestures { tap ->
                        // Handle tap selection logic
                        if (userLoc == null) return@detectTapGestures
                        
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val r = minOf(size.width, size.height) / 2f
                        
                        waypoints.forEach { wp ->
                            val dist = userLoc.distTo(wp)
                            if (dist <= zoomScale) {
                                val bearing = userLoc.bearingTo(wp)
                                val pxDist = (dist / zoomScale) * (r * 0.75f)
                                val dispAz = if (locked) 0f else azimuth
                                val rad = Math.toRadians((bearing - dispAz - 90).toDouble())
                                val wx = cx + (pxDist * cos(rad)).toFloat()
                                val wy = cy + (pxDist * sin(rad)).toFloat()
                                
                                if (hypot(tap.x - wx, tap.y - wy) < 50f) onSelect(wp)
                            }
                        }
                    }
                }
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val r = minOf(size.width, size.height) / 2f
            val dialR = r * 0.95f
            val rotation = if (locked) 0f else azimuth

            // Draw range rings
            listOf(100f, 250f, 500f).forEach { rng ->
                if (rng <= zoomScale) {
                    val rr = (rng / zoomScale) * (r * 0.75f)
                    drawCircle(ringColor, radius = rr, style = Stroke(1.dp.toPx()))
                    drawText(textMeasurer, "${rng.toInt()}m", Offset(cx + rr - 20, cy - 8), TextStyle(colors.textSec, 10.sp))
                }
            }

            rotate(-rotation, pivot = Offset(cx, cy)) {
                // Outer bezel
                drawCircle(colors.card, radius = r)
                drawCircle(ringColor, radius = r, style = Stroke(2f))

                // Ticks
                for (i in 0 until 360 step 5) {
                    val isCard = i % 90 == 0
                    val len = if (isCard) 25f else if (i % 30 == 0) 15f else 8f
                    val col = if (isCard) colors.textPri else if (i % 30 == 0) colors.textSec else ringColor
                    val rad = Math.toRadians((i - 90).toDouble())
                    
                    val sx = cx + (dialR * cos(rad)).toFloat()
                    val sy = cy + (dialR * sin(rad)).toFloat()
                    val ex = cx + ((dialR - len) * cos(rad)).toFloat()
                    val ey = cy + ((dialR - len) * sin(rad)).toFloat()
                    
                    drawLine(col, Offset(sx, sy), Offset(ex, ey), strokeWidth = if (isCard) 3f else 1f)
                }

                // N, S, E, W
                val cardR = dialR - 40f
                drawText(textMeasurer, "N", Offset(cx-12, cy-cardR-5), TextStyle(CyanAccent, 22.sp, FontWeight.Bold))
                drawText(textMeasurer, "S", Offset(cx-8, cy+cardR-20), TextStyle(colors.textPri, 20.sp))
                drawText(textMeasurer, "E", Offset(cx+cardR-18, cy-10), TextStyle(colors.textPri, 20.sp))
                drawText(textMeasurer, "W", Offset(cx-cardR-5, cy-10), TextStyle(colors.textPri, 20.sp))

                // Waypoints dots
                userLoc?.let { loc ->
                    waypoints.forEach { wp ->
                        val dist = loc.distTo(wp)
                        if (dist <= zoomScale) {
                            val bear = loc.bearingTo(wp)
                            val pxDist = (dist / zoomScale) * (r * 0.75f)
                            val rad = Math.toRadians((bear - 90).toDouble())
                            val wx = cx + (pxDist * cos(rad)).toFloat()
                            val wy = cy + (pxDist * sin(rad)).toFloat()
                            val isSel = wp.id == target?.id
                            val col = getCategoryColor(wp.category)

                            drawCircle(col, radius = if (isSel) 16f else 10f, center = Offset(wx, wy))
                            if (isSel) {
                                drawLine(col.copy(0.6f), Offset(cx, cy), Offset(wx, wy), strokeWidth = 3f, 
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(15f, 8f)))
                            }
                        }
                    }
                }
            }

            // Direction Arrow
            val arrowAngle = if (target != null && userLoc != null) {
                userLoc.bearingTo(target) - azimuth
            } else 0f

            rotate(arrowAngle, pivot = Offset(cx, cy)) {
                val path = Path().apply {
                    moveTo(cx, cy - (r * 0.35f))
                    lineTo(cx - 18f, cy + 15f)
                    lineTo(cx, cy - 5f)
                    lineTo(cx + 18f, cy + 15f)
                    close()
                }
                drawPath(path, NeedleWhite)
            }
            drawCircle(colors.textPri, radius = 6f, center = Offset(cx, cy))
        }
    }
}

@Composable
fun SavedPointsScreen(
    waypoints: List<Waypoint>,
    location: Location?,
    azimuth: Float,
    selected: Waypoint?,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onSelect: (Waypoint) -> Unit,
    onAdd: () -> Unit,
    isDark: Boolean
) {
    val colors = if (isDark) DarkThemeColors else LightThemeColors

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colors.textPri)
            }
            Text("Saved Points", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.textPri)
            Spacer(Modifier.weight(1f))
        }

        if (waypoints.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No saved points yet", color = colors.textSec)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(waypoints) { wp ->
                    WaypointRow(wp, location, azimuth, wp.id == selected?.id, isDark, onSelect = { onSelect(wp) })
                }
            }
        }

        Row(Modifier.padding(16.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Text("Add Point")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClear) {
                Text("Clear All", color = ErrorRed)
            }
        }
    }
}

@Composable
fun WaypointRow(wp: Waypoint, loc: Location?, az: Float, active: Boolean, isDark: Boolean, onSelect: () -> Unit) {
    val colors = if (isDark) DarkThemeColors else LightThemeColors
    val catColor = getCategoryColor(wp.category)
    
    // Calculate dist/bearing
    var info = "--"
    var bear = 0f
    if (loc != null) {
        val d = loc.distTo(wp)
        bear = loc.bearingTo(wp)
        info = if (d > 1000) String.format("%.1f km", d/1000) else "${d.toInt()} m"
    }

    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(colors.card),
        border = if (active) BorderStroke(2.dp, BlueAccent) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(catColor.copy(0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(wp.category.icon) // Emoji icon
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(wp.name, fontWeight = FontWeight.Bold, color = colors.textPri)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info, color = colors.textSec, fontSize = 12.sp)
                    if (loc != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Navigation, null, modifier = Modifier.size(12.dp).rotate(bear - az), tint = colors.textSec)
                    }
                }
            }
        }
    }
}

@Composable
fun RadarScreen(
    location: Location?,
    history: List<TrailPoint>,
    waypoints: List<Waypoint>,
    azimuth: Float,
    target: Waypoint?,
    accuracy: Float,
    isTracking: Boolean,
    onSelect: (Waypoint) -> Unit,
    onAdd: () -> Unit,
    isDark: Boolean
) {
    var scale by remember { mutableFloatStateOf(2f) } // zoom
    val colors = if (isDark) DarkThemeColors else LightThemeColors

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // Radar Header
        Surface(color = colors.surface, shadowElevation = 4.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RADAR", fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = colors.textPri)
                Text(if (isTracking) "GPS: ON (±${accuracy.toInt()}m)" else "GPS: OFF", color = if(isTracking) SuccessGreen else ErrorRed, fontSize = 12.sp)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(10.dp)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF0A0E14))
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ -> scale = (scale * z).coerceIn(0.5f, 10f) }
                }
        ) {
            if (location == null) {
                Text("Waiting for GPS...", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val cx = size.width/2
                    val cy = size.height/2
                    rotate(-azimuth, pivot = Offset(cx, cy)) {
                        // Draw trail
                        if (history.isNotEmpty()) {
                            val path = Path()
                            val lonScale = cos(Math.toRadians(location.latitude))
                            
                            history.forEachIndexed { i, pt ->
                                val dy = (pt.lat - location.latitude) * 111139.0
                                val dx = (pt.lon - location.longitude) * 111139.0 * lonScale
                                val px = cx + (dx * scale).toFloat()
                                val py = cy - (dy * scale).toFloat()
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            drawPath(path, LimeAccent, style = Stroke(3f))
                        }
                        
                        // Draw waypoints
                        waypoints.forEach { wp ->
                            val dy = (wp.latitude - location.latitude) * 111139.0
                            val dx = (wp.longitude - location.longitude) * 111139.0 * cos(Math.toRadians(location.latitude))
                            val wx = cx + (dx * scale).toFloat()
                            val wy = cy - (dy * scale).toFloat()
                            
                            drawCircle(getCategoryColor(wp.category), radius = 15f, center = Offset(wx, wy))
                        }
                    }
                    
                    // Me
                    drawCircle(CyanAccent, radius = 10f, center = Offset(cx, cy))
                }
            }
        }
        
        Button(onClick = onAdd, modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Add Marker")
        }
    }
}

// Dialogs 
@Composable
fun PermissionDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, isDark: Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable GPS?") },
        text = { Text("Compass needs location to track trails and direction.") },
        confirmButton = { Button(onClick = onConfirm) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No") } }
    )
}

@Composable
fun AddPointDialog(onDismiss: () -> Unit, onConfirm: (String, WaypointCategory, String) -> Unit, isDark: Boolean) {
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Point") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note") })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name, WaypointCategory.CUSTOM, note) }) { Text("Save") } }
    )
}

@Composable
fun DeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, isDark: Boolean) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete All?") },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }, colors = ButtonDefaults.buttonColors(ErrorRed)) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Utils
fun Location.distTo(wp: Waypoint): Float {
    val r = floatArrayOf(0f)
    Location.distanceBetween(latitude, longitude, wp.latitude, wp.longitude, r)
    return r[0]
}
fun Location.bearingTo(wp: Waypoint): Float {
    val r = floatArrayOf(0f, 0f)
    Location.distanceBetween(latitude, longitude, wp.latitude, wp.longitude, r)
    return r[1]
}

fun getCategoryColor(cat: WaypointCategory) = when(cat) {
    WaypointCategory.CAMP -> CampColor
    WaypointCategory.WATER -> WaterColor
    WaypointCategory.DANGER -> DangerColor
    WaypointCategory.VIEW -> ViewpointColor
    WaypointCategory.EXIT -> ExitColor
    else -> LimeAccent
}