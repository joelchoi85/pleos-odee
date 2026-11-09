package com.hyundai.pleos.syncdrive.sdk

import android.content.Context
import android.content.Intent
import org.json.JSONObject

import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hyundai Pleos Connect SDK (Vehicle / Navi / Gleo AI) 및 AAOS 시뮬레이터 브릿지 공통 추상화 게이트웨이.
 */
interface VehiclePlatformGateway {
    val telemetryFlow: StateFlow<LocalVehicleSnapshot>
    val ambientColorFlow: StateFlow<AmbientAlertColor>

    suspend fun updateNavigationWaypoint(lat: Double, lng: Double, title: String): Boolean
    suspend fun setAmbientAlertState(alertColor: AmbientAlertColor)
    suspend fun restoreAmbientState()
    fun speakBriefing(text: String, onDone: () -> Unit = {})
    fun startVoiceConfirmation(onCommandRecognized: (VoiceDecision) -> Unit)
    /** 알림이 닫히면 반드시 호출: 마이크를 계속 열어두면 CPU·프라이버시 기준 위반 */
    fun stopVoiceConfirmation() {}
}

/**
 * Gleo AI 또는 Android SpeechRecognizer 자연어 발화를 VoiceDecision 으로 매핑합니다.
 */
object VoiceCommandParser {
    fun parse(rawUtterance: String?): VoiceDecision? {
        val normalized = rawUtterance?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

        // Direct enum match
        VoiceDecision.entries.firstOrNull { it.name.lowercase() == normalized }?.let { return it }

        // Route-only phrases (checked before generic accept phrases)
        val routeOnlyKeywords = listOf(
            "경로만", "네비만", "내비만", "통화 없이", "통화없이", "조용히", "route only", "accept_route_only"
        )
        if (routeOnlyKeywords.any { normalized.contains(it) }) {
            return VoiceDecision.ACCEPT_ROUTE_ONLY
        }

        // Reject / keep existing route phrases
        val keepKeywords = listOf(
            "유지", "기존", "거절", "취소", "아니", "무시", "그대로", "안 해", "안해", "keep", "cancel", "ignore", "reject"
        )
        if (keepKeywords.any { normalized.contains(it) }) {
            return VoiceDecision.KEEP_ROUTE
        }

        // Accept route + connect voice call phrases
        val acceptAndCallKeywords = listOf(
            "수락", "연결", "통화", "받아", "보이스", "반영", "경유", "우회", "좋아", "그래", "응", "네", "예", "오케이", "ok", "accept", "call", "yes"
        )
        if (acceptAndCallKeywords.any { normalized.contains(it) }) {
            return VoiceDecision.ACCEPT_AND_CALL
        }

        return null
    }
}

/**
 * Gleo Interface 브릿지 (assets/schema.json 의 actionCalls 와 1:1).
 * Gleo는 action=function name, extra "ACTION_PARAM"=파라미터 JSON 으로 보내고,
 * 앱은 APP_ACTION_RESPONSE 브로드캐스트로 결과를 돌려준다.
 */
object GleoIntentEventBus {
    const val ACTION_SHOW_GROUP_LOCATION = "SHOW_GROUP_LOCATION"
    const val ACTION_ANSWER_GROUP_ALERT = "ANSWER_GROUP_ALERT"
    const val ACTION_CONTROL_VOICE_CALL = "CONTROL_VOICE_CALL"
    private const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
    private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

    enum class VoiceCallAction { END_CALL, TOGGLE_MUTE, MUTE, UNMUTE }

    /** 문서상 파라미터가 개별 extra 또는 ACTION_PARAM JSON 둘 다 가능 → 둘 다 확인 */
    fun param(intent: Intent, key: String): String? =
        intent.getStringExtra(key) ?: runCatching {
            JSONObject(intent.getStringExtra(EXTRA_ACTION_PARAM) ?: "{}").optString(key).ifEmpty { null }
        }.getOrNull()

    fun respond(context: Context, functionName: String, success: Boolean, message: String) {
        val response = JSONObject()
            .put("functionName", functionName)
            .put("status", if (success) "success" else "fail")
            .put("message", message)
        context.sendBroadcast(
            Intent(ACTION_RESPONSE)
                .putExtra("packageName", context.packageName)
                .putExtra("response", response.toString())
        )
    }

    private val _decisions = MutableSharedFlow<VoiceDecision>(extraBufferCapacity = 8)
    val decisions: SharedFlow<VoiceDecision> = _decisions.asSharedFlow()

    private val _callCommands = MutableSharedFlow<VoiceCallAction>(extraBufferCapacity = 8)
    val callCommands: SharedFlow<VoiceCallAction> = _callCommands.asSharedFlow()

    /** 통화 연결 여부 (ViewModel이 갱신). 통화가 없을 때 Gleo에 거짓 성공 응답을 하지 않기 위함 */
    @Volatile var isCallActive: Boolean = false

    fun emitCallAction(action: VoiceCallAction) {
        _callCommands.tryEmit(action)
    }

    fun parseCallAction(raw: String?): VoiceCallAction? {
        val normalized = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        VoiceCallAction.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }?.let { return it }

        val lower = normalized.lowercase()
        if (lower.contains("끊") || lower.contains("종료") || lower.contains("end") || lower.contains("hangup") || lower.contains("leave")) {
            return VoiceCallAction.END_CALL
        }
        // 토글을 먼저: "toggle mute"가 mute에 걸리지 않게. 영어 "on/off" 부분일치는 오탐("mute on"→켜기)이라 제외
        if (lower.contains("토글") || lower.contains("toggle")) {
            return VoiceCallAction.TOGGLE_MUTE
        }
        if (lower.contains("켜") || lower.contains("unmute") || lower.contains("해제")) {
            return VoiceCallAction.UNMUTE
        }
        if (lower.contains("꺼") || lower.contains("뮤트") || lower.contains("mute") || lower.contains("음소거")) {
            return VoiceCallAction.MUTE
        }
        return null
    }

    fun tryEmitFromRawCommand(rawText: String?): Boolean {
        val parsed = VoiceCommandParser.parse(rawText) ?: return false
        return _decisions.tryEmit(parsed)
    }

    fun emitDecision(decision: VoiceDecision) {
        _decisions.tryEmit(decision)
    }
}
