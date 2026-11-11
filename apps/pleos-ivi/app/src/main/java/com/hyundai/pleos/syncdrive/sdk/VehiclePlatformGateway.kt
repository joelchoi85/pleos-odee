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
 * Gleo는 action="패키지명.function name"(AmbientAi AppActionViewModel.sendActionCall 실측), extra "ACTION_PARAM"=파라미터 JSON 으로 보내고,
 * 앱은 APP_ACTION_RESPONSE 브로드캐스트로 결과를 돌려준다.
 */
object GleoIntentEventBus {
    const val ACTION_SHOW_GROUP_LOCATION = "com.hyundai.pleos.syncdrive.SHOW_GROUP_LOCATION"
    const val ACTION_ANSWER_GROUP_ALERT = "com.hyundai.pleos.syncdrive.ANSWER_GROUP_ALERT"
    private const val EXTRA_ACTION_PARAM = "ACTION_PARAM"
    private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"

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

    /** "일행 어디쯤?" 음성 응답 문장 (ViewModel이 갱신) */
    @Volatile var groupSummary: String = "아직 연결된 일행이 없습니다."

    fun tryEmitFromRawCommand(rawText: String?): Boolean {
        val parsed = VoiceCommandParser.parse(rawText) ?: return false
        return _decisions.tryEmit(parsed)
    }

    fun emitDecision(decision: VoiceDecision) {
        _decisions.tryEmit(decision)
    }
}
