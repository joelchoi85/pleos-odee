package com.hyundai.pleos.syncdrive.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AlertCountdownState(
    val activeEventId: String? = null,
    val totalSeconds: Int = 8,
    val remainingSeconds: Int = 0,
    val isRunning: Boolean = false
) {
    val progressFraction: Float
        get() = if (!isRunning || totalSeconds <= 0) {
            0f
        } else {
            (remainingSeconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * GROUP_ALERT 수신 시 8초(또는 서버 지정 timeoutSeconds) 카운트다운을 수행하고,
 * 무응답 타임아웃 시 안전 기본값(VoiceDecision.KEEP_ROUTE)으로 자동 복귀하는 상태 머신입니다.
 */
class AlertCountdownCoordinator(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AlertCountdownState())
    val state: StateFlow<AlertCountdownState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    fun startCountdown(
        eventId: String,
        timeoutSeconds: Int = 8,
        onTimeoutSafeDefault: suspend (VoiceDecision) -> Unit
    ) {
        countdownJob?.cancel()
        val sanitizedTotal = timeoutSeconds.coerceAtLeast(1)
        _state.value = AlertCountdownState(
            activeEventId = eventId,
            totalSeconds = sanitizedTotal,
            remainingSeconds = sanitizedTotal,
            isRunning = true
        )

        countdownJob = scope.launch {
            var remaining = sanitizedTotal
            while (remaining > 0) {
                delay(1_000L)
                remaining -= 1
                _state.value = _state.value.copy(remainingSeconds = remaining)
            }
            _state.value = AlertCountdownState()
            onTimeoutSafeDefault(VoiceDecision.KEEP_ROUTE)
        }
    }

    fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        _state.value = AlertCountdownState()
    }
}
