package com.hyundai.pleos.syncdrive.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtaClockTest {
    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun utcEtaShownInLocalTime() {
        // 서버·시뮬레이터는 toISOString()(UTC)로 보냄 → 12:30Z는 서울 21:30
        assertEquals("21:30", formatEtaClock("2026-11-09T12:30:00.000Z", seoul))
    }

    @Test
    fun invalidEtaIsNull() {
        assertNull(formatEtaClock("", seoul))
    }
}
