package com.hyundai.pleos.syncdrive.ui.map

import com.hyundai.pleos.syncdrive.domain.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteGeometryTest {
    private val route = (0..10).map { GeoPoint(37.0 + it * 0.01, 127.0) }

    @Test
    fun progressIsFractionOfRoute() {
        assertEquals(0f, progressAlongRoute(route, GeoPoint(37.0, 127.0)), 0.01f)
        assertEquals(0.5f, progressAlongRoute(route, GeoPoint(37.05, 127.001)), 0.01f)
        assertEquals(1f, progressAlongRoute(route, GeoPoint(37.2, 127.0)), 0.01f)
    }

    @Test
    fun closeVehiclesAlternateLanes() {
        assertEquals(listOf(0, 1, -1, 0), assignLanes(listOf(0.10f, 0.11f, 0.12f, 0.13f), 0.05f))
        assertEquals(listOf(0, 0), assignLanes(listOf(0.1f, 0.9f), 0.05f))
    }
}
