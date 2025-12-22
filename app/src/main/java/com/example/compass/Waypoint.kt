package com.example.compass

import java.util.UUID

data class TrailPoint(
    val lat: Double,
    val lon: Double,
    val time: Long = System.currentTimeMillis()
)

enum class WaypointCategory(val label: String, val icon: String) {
    CAMP("Camp", "🏕️"),
    WATER("Water", "💧"),
    DANGER("Danger", "⚠️"),
    VIEW("View", "👁️"),
    EXIT("Exit", "🚪"),
    CUSTOM("Point", "📍")
}

data class Waypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val category: WaypointCategory = WaypointCategory.CUSTOM,
    val notes: String = ""
)