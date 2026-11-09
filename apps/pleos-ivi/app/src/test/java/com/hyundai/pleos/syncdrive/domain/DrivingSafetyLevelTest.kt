package com.hyundai.pleos.syncdrive.domain

import com.hyundai.pleos.syncdrive.sdk.SimulatorBridgeGatewayImpl
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingSafetyLevelTest {

    @Test
    fun `0 kmh in Gear P returns STATIONARY`() {
        assertEquals(
            DrivingSafetyLevel.STATIONARY,
            deriveDrivingSafetyLevel(speedKmh = 0.0, gear = GearState.P, isBraking = false)
        )
        // Even with brake foot pressed while parked at 0 km/h in P, vehicle is stationary
        assertEquals(
            DrivingSafetyLevel.STATIONARY,
            deriveDrivingSafetyLevel(speedKmh = 0.8, gear = GearState.P, isBraking = true)
        )
    }

    @Test
    fun `0 kmh in Gear D returns NORMAL_DRIVING when not braking and HIGH_FOCUS when braking`() {
        assertEquals(
            DrivingSafetyLevel.NORMAL_DRIVING,
            deriveDrivingSafetyLevel(speedKmh = 0.0, gear = GearState.D, isBraking = false)
        )
        assertEquals(
            DrivingSafetyLevel.HIGH_FOCUS,
            deriveDrivingSafetyLevel(speedKmh = 0.0, gear = GearState.D, isBraking = true)
        )
    }

    @Test
    fun `40 kmh in Gear D transitions between NORMAL_DRIVING and HIGH_FOCUS on braking`() {
        assertEquals(
            DrivingSafetyLevel.NORMAL_DRIVING,
            deriveDrivingSafetyLevel(speedKmh = 40.0, gear = GearState.D, isBraking = false)
        )
        assertEquals(
            DrivingSafetyLevel.HIGH_FOCUS,
            deriveDrivingSafetyLevel(speedKmh = 40.0, gear = GearState.D, isBraking = true)
        )
    }

    @Test
    fun `90 kmh in Gear D always returns HIGH_FOCUS`() {
        assertEquals(
            DrivingSafetyLevel.HIGH_FOCUS,
            deriveDrivingSafetyLevel(speedKmh = 90.0, gear = GearState.D, isBraking = false)
        )
        assertEquals(
            DrivingSafetyLevel.HIGH_FOCUS,
            deriveDrivingSafetyLevel(speedKmh = 90.0, gear = GearState.D, isBraking = true)
        )
        assertEquals(
            DrivingSafetyLevel.HIGH_FOCUS,
            deriveDrivingSafetyLevel(speedKmh = 60.0, gear = GearState.D, isBraking = false)
        )
    }

    @Test
    fun `SimulatorBridgeGatewayImpl runtime presets transition safetyLevel accurately`() {
        val gateway = SimulatorBridgeGatewayImpl()
        assertEquals(DrivingSafetyLevel.STATIONARY, gateway.telemetryFlow.value.safetyLevel)

        gateway.applyUrbanDrivingPreset()
        assertEquals(45.0, gateway.telemetryFlow.value.speedKmh, 0.01)
        assertEquals(GearState.D, gateway.telemetryFlow.value.gear)
        assertEquals(DrivingSafetyLevel.NORMAL_DRIVING, gateway.telemetryFlow.value.safetyLevel)

        gateway.setBraking(true)
        assertEquals(DrivingSafetyLevel.HIGH_FOCUS, gateway.telemetryFlow.value.safetyLevel)

        gateway.setBraking(false)
        assertEquals(DrivingSafetyLevel.NORMAL_DRIVING, gateway.telemetryFlow.value.safetyLevel)

        gateway.applyHighwayHighFocusPreset()
        assertEquals(90.0, gateway.telemetryFlow.value.speedKmh, 0.01)
        assertEquals(DrivingSafetyLevel.HIGH_FOCUS, gateway.telemetryFlow.value.safetyLevel)

        gateway.applyStationaryParkPreset()
        assertEquals(DrivingSafetyLevel.STATIONARY, gateway.telemetryFlow.value.safetyLevel)
    }
}
