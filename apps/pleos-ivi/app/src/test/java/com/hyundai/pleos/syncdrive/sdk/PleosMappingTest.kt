package com.hyundai.pleos.syncdrive.sdk

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.hyundai.pleos.syncdrive.domain.DrivingSafetyLevel
import com.hyundai.pleos.syncdrive.domain.GearState
import com.hyundai.pleos.syncdrive.domain.deriveDrivingSafetyLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class PleosMappingTest {
    @Test
    fun gearMapping() {
        assertEquals(GearState.P, VehicleGear.GEAR_PARK.toGearState())
        assertEquals(GearState.R, VehicleGear.GEAR_REVERSE.toGearState())
        assertEquals(GearState.D, VehicleGear.GEAR_3.toGearState())
    }

    @Test
    fun haversineOneDegreeLatIsAbout111Km() {
        assertEquals(111_195.0, haversineMeters(37.0, 127.0, 38.0, 127.0), 200.0)
    }

    @Test
    fun uxRestrictionBlocksStationaryUi() {
        assertEquals(DrivingSafetyLevel.STATIONARY, deriveDrivingSafetyLevel(0.0, GearState.P, false))
        assertEquals(DrivingSafetyLevel.NORMAL_DRIVING, deriveDrivingSafetyLevel(0.0, GearState.P, false, isUxRestricted = true))
    }
}
