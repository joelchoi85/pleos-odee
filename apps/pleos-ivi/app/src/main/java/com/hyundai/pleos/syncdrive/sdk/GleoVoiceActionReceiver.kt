package com.hyundai.pleos.syncdrive.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Gleo SHOW_GROUP_LOCATION·ANSWER_GROUP_ALERT·CONTROL_VOICE_CALL (component=service → sendBroadcast) 수신 */
class GleoVoiceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // 지도를 가리지 않도록 화면은 띄우지 않고 음성 응답만. 드로워에 오디가 열려 있으면 거기서 보임
            GleoIntentEventBus.ACTION_SHOW_GROUP_LOCATION -> GleoIntentEventBus.respond(
                context, GleoIntentEventBus.ACTION_SHOW_GROUP_LOCATION, true, GleoIntentEventBus.groupSummary
            )
            GleoIntentEventBus.ACTION_ANSWER_GROUP_ALERT -> {
                val handled = GleoIntentEventBus.tryEmitFromRawCommand(GleoIntentEventBus.param(intent, "decision"))
                GleoIntentEventBus.respond(
                    context, GleoIntentEventBus.ACTION_ANSWER_GROUP_ALERT, handled,
                    if (handled) "오디 알림에 응답했습니다." else "응답할 오디 알림이 없습니다."
                )
            }
            GleoIntentEventBus.ACTION_CONTROL_VOICE_CALL -> {
                val rawAction = GleoIntentEventBus.param(intent, "action")
                val parsedAction = GleoIntentEventBus.parseCallAction(rawAction)
                if (parsedAction != null && !GleoIntentEventBus.isCallActive) {
                    GleoIntentEventBus.respond(context, GleoIntentEventBus.ACTION_CONTROL_VOICE_CALL, false, "진행 중인 오디 통화가 없습니다.")
                } else if (parsedAction != null) {
                    GleoIntentEventBus.emitCallAction(parsedAction)
                    val msg = when (parsedAction) {
                        GleoIntentEventBus.VoiceCallAction.END_CALL -> "통화를 종료합니다."
                        GleoIntentEventBus.VoiceCallAction.MUTE -> "마이크를 끕니다."
                        GleoIntentEventBus.VoiceCallAction.UNMUTE -> "마이크를 켭니다."
                        GleoIntentEventBus.VoiceCallAction.TOGGLE_MUTE -> "마이크 상태를 변경합니다."
                    }
                    GleoIntentEventBus.respond(context, GleoIntentEventBus.ACTION_CONTROL_VOICE_CALL, true, msg)
                } else {
                    GleoIntentEventBus.respond(
                        context, GleoIntentEventBus.ACTION_CONTROL_VOICE_CALL, false,
                        "통화 제어 명령을 이해하지 못했습니다: $rawAction"
                    )
                }
            }
        }
    }
}
