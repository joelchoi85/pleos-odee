package com.hyundai.pleos.syncdrive.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupLocationSummaryTest {
    // 북쪽으로 곧게 뻗은 경로, 위도 0.01° ≈ 1.11km
    private val route = (0..50).map { GeoPoint(36.0 + it * 0.01, 128.0) }

    private fun car(name: String, lat: Double?, eta: String = "14:45 도착", offRoute: Boolean = false, role: MemberRole = MemberRole.PLEOS_COMPANION) =
        CompanionVehicleUiModel(
            memberId = name, displayName = name, role = role, powertrain = PowertrainType.EV,
            energyPercent = 80, remainingRangeKm = null, speedKmh = 90, gear = GearState.D,
            etaDisplay = eta, isOffRoute = offRoute, crossTrackDistanceMeters = 0, inVoiceCall = false,
            isSelfHost = false, lat = lat, lng = lat?.let { 128.0 }
        )

    @Test
    fun `ahead behind and near relative to my car along route`() {
        val me = LocalVehicleSnapshot(lat = 36.10, lng = 128.0)
        val summary = buildGroupLocationSummary(
            me,
            listOf(
                car("EV9 (2호차 · 김민준)", 36.39),                       // 약 32km 앞
                car("싼타페 HEV (3호차 · 박서연)", 36.05, offRoute = true), // 약 6km 뒤
                car("팰리세이드", 36.101),                                 // 100m
                car("이지훈 (동승)", 36.10, role = MemberRole.MOBILE_NAVIGATOR)
            ),
            route
        )
        assertEquals(
            "2호차 EV9 32km 앞, 14:45 도착. 3호차 싼타페 HEV 6km 뒤, 경로 이탈 중. 팰리세이드 바로 근처, 14:45 도착.",
            summary
        )
    }

    @Test
    fun `no route or coordinates falls back to eta only`() {
        val summary = buildGroupLocationSummary(LocalVehicleSnapshot(lat = 36.1, lng = 128.0), listOf(car("EV9 (2호차 · 김민준)", null)), emptyList())
        assertEquals("2호차 EV9 14:45 도착.", summary)
    }
}
