package com.hyundai.pleos.syncdrive.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyundai.pleos.syncdrive.audio.ActiveVoiceCallState
import com.hyundai.pleos.syncdrive.audio.VoiceRoomState
import com.hyundai.pleos.syncdrive.domain.AlertCountdownState
import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.DrivingSafetyLevel
import com.hyundai.pleos.syncdrive.domain.GroupAlertUiModel
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import com.hyundai.pleos.syncdrive.ui.theme.PleosColors

/**
 * 우측 1/3 주행 안전 적응형 분할 오버레이 (SafetyAdaptiveOverlay).
 *
 * - GROUP_ALERT 활성 시 8초 카운트다운 프로그레스 바와 함께 주행 안전 등급(HIGH_FOCUS vs NORMAL_DRIVING)별 UI 전환
 * - HIGH_FOCUS (60km/h+ 또는 제동 중): 초대형 헤드라인, ETA 증감 배지(+5분 Amber / 8분 단축 Emerald),
 *   TTS + 음성 명령 대기 인디케이터, 단일 초대형 [음성/원터치 수락 · 보이스 연결] 버튼 제공 (타임아웃 시 KEEP_ROUTE 복귀)
 * - NORMAL_DRIVING / STATIONARY: 기존 vs 제안 경로 매트릭스 비교 카드 + [기존 경로 유지] 및 [경로 반영 + 보이스 연결] 대형 버튼 제공
 */
@Composable
fun SafetyAdaptiveOverlayPanel(
    safetyLevel: DrivingSafetyLevel,
    activeAlert: GroupAlertUiModel?,
    countdown: AlertCountdownState,
    voiceCall: ActiveVoiceCallState,
    isVoiceListening: Boolean,
    onDecision: (VoiceDecision) -> Unit,
    onToggleMute: () -> Unit,
    onHangUpCall: () -> Unit,
    modifier: Modifier = Modifier,
    isHardBrakingAudioMuted: Boolean = false,
    isDirectCall: Boolean = false
) {
    val accentColor by animateColorAsState(
        targetValue = when {
            isHardBrakingAudioMuted -> PleosColors.CoralDanger
            activeAlert != null -> when (activeAlert.ambientColor) {
                AmbientAlertColor.AMBER_DEVIATION -> PleosColors.AmberAlert
                AmbientAlertColor.CYAN_PROPOSAL -> PleosColors.PleosCyan
                AmbientAlertColor.GREEN_VOICE_ACTIVE -> PleosColors.EmeraldVoice
                AmbientAlertColor.NONE -> PleosColors.PleosCyan
            }
            voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED -> PleosColors.EmeraldVoice
            else -> PleosColors.BorderSubtle
        },
        animationSpec = tween(350),
        label = "rightPanelBorderColor"
    )

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .border(
                width = if (isHardBrakingAudioMuted) 3.dp else if (activeAlert != null || voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED) 2.dp else 1.dp,
                color = accentColor,
                shape = RoundedCornerShape(22.dp)
            ),
        shape = RoundedCornerShape(22.dp),
        color = PleosColors.PanelSurface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (activeAlert != null) {
                    ActiveAlertOverlayContent(
                        safetyLevel = safetyLevel,
                        alert = activeAlert,
                        countdown = countdown,
                        accentColor = accentColor,
                        isVoiceListening = isVoiceListening,
                        onDecision = onDecision
                    )
                } else {
                    StandbyVoiceAndSyncDock(
                        safetyLevel = safetyLevel,
                        voiceCall = voiceCall,
                        isDirectCall = isDirectCall,
                        onToggleMute = onToggleMute,
                        onHangUpCall = onHangUpCall
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveAlertOverlayContent(
    safetyLevel: DrivingSafetyLevel,
    alert: GroupAlertUiModel,
    countdown: AlertCountdownState,
    accentColor: Color,
    isVoiceListening: Boolean,
    onDecision: (VoiceDecision) -> Unit
) {
    val etaBadgeColor = if (alert.isTimeSaving) {
        PleosColors.EmeraldVoice
    } else {
        PleosColors.AmberAlert
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.16f),
                        PleosColors.PanelSurface
                    )
                )
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Countdown Header
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = alert.categoryBadgeKo,
                        color = PleosColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = "${countdown.remainingSeconds}초",
                    color = accentColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            LinearProgressIndicator(
                progress = { countdown.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = accentColor,
                trackColor = PleosColors.CardSurfaceElevated
            )
        }

        // 통화 요청은 경로 비교가 없으니 주행 등급과 무관하게 단일 버튼 화면 (무응답 = 거절)
        if (safetyLevel == DrivingSafetyLevel.HIGH_FOCUS || alert.isCallInvite) {
            // HIGH_FOCUS: Ultra-minimal glanceable UI + Voice prompt + Single giant safe touch target
            HighFocusAlertBody(
                alert = alert,
                etaBadgeColor = etaBadgeColor,
                isVoiceListening = isVoiceListening
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onDecision(VoiceDecision.ACCEPT_AND_CALL) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PleosColors.EmeraldVoice,
                        contentColor = Color(0xFF031A11)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (alert.isCallInvite) "통화 받기" else "음성/원터치 수락 · 보이스 연결",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Text(
                    text = if (alert.isCallInvite) "무응답 시 ${countdown.remainingSeconds}초 후 자동 거절"
                        else "고속/제동 집중 모드 · 무응답 시 ${countdown.remainingSeconds}초 후 기존 경로 자동 유지",
                    color = PleosColors.TextSecondary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // NORMAL_DRIVING & STATIONARY: Matrix Comparison Cards + Dual Oversized Automotive Buttons
            NormalDrivingMatrixBody(
                alert = alert,
                etaBadgeColor = etaBadgeColor,
                isVoiceListening = isVoiceListening
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onDecision(VoiceDecision.ACCEPT_AND_CALL) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color(0xFF04131C)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "경로 반영 + 보이스 연결",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                OutlinedButton(
                    onClick = { onDecision(VoiceDecision.KEEP_ROUTE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, PleosColors.BorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = PleosColors.CardSurface,
                        contentColor = PleosColors.TextPrimary
                    )
                ) {
                    Text(
                        text = "기존 경로 유지 (${countdown.remainingSeconds}s)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun HighFocusAlertBody(
    alert: GroupAlertUiModel,
    etaBadgeColor: Color,
    isVoiceListening: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            color = etaBadgeColor.copy(alpha = 0.22f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, etaBadgeColor)
        ) {
            Text(
                text = alert.formattedEtaBadgeKo,
                color = etaBadgeColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        Text(
            text = alert.headlineKo,
            color = PleosColors.TextPrimary,
            fontSize = 26.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )

        alert.recommendedWaypoint?.let { wp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Navigation,
                    contentDescription = null,
                    tint = PleosColors.PleosCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = wp.name,
                    color = PleosColors.PleosCyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Surface(
            color = PleosColors.CardSurfaceElevated,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (isVoiceListening) PleosColors.EmeraldVoice else PleosColors.BorderSubtle
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.RecordVoiceOver,
                    contentDescription = null,
                    tint = PleosColors.EmeraldVoice,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = if (isVoiceListening) {
                            "Gleo AI 음성 브리핑 및 음성 인식 활성"
                        } else {
                            "Gleo AI 핸즈프리 음성 명령 대기"
                        },
                        color = PleosColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "\"수락\" 또는 \"기존 경로 유지\"라고 말씀하세요",
                        color = PleosColors.EmeraldVoice,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun NormalDrivingMatrixBody(
    alert: GroupAlertUiModel,
    etaBadgeColor: Color,
    isVoiceListening: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = alert.headlineKo,
            color = PleosColors.TextPrimary,
            fontSize = 21.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // Matrix Comparison Cards: [기존 경로] vs [제안 경로]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                color = PleosColors.CardSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, PleosColors.BorderSubtle)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "현재 경로 유지",
                        color = PleosColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "ETA 변동 없음",
                        color = PleosColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${alert.sourceDisplayName} 개별 이동",
                        color = PleosColors.TextMuted,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                color = etaBadgeColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, etaBadgeColor)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "그룹 동반 반영",
                        color = etaBadgeColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = alert.formattedEtaBadgeKo,
                        color = etaBadgeColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = alert.recommendedWaypoint?.name ?: "추천 경유지 합류",
                        color = PleosColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Surface(
            color = PleosColors.CardSurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = alert.detailKo,
                    color = PleosColors.TextSecondary,
                    fontSize = 17.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (isVoiceListening) {
                    Text(
                        text = "Gleo 음성 인식 중: '수락' / '경로만' / '유지'",
                        color = PleosColors.PleosCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceCallControlCard(
    voiceCall: ActiveVoiceCallState,
    isDirectCall: Boolean,
    onToggleMute: () -> Unit,
    onHangUpCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val titleText = if (isDirectCall) "🎙️ 1:1 통화 중" else "🎙️ 그룹 통화 중"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // 헤더 영역: 26sp 이상의 굵은 글씨
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(PleosColors.EmeraldVoice)
                )
                Text(
                    text = titleText,
                    color = PleosColors.EmeraldVoice,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black
                )
            }

            // 발화자 상태 / 서브텍스트 카드 (18sp)
            Surface(
                color = PleosColors.CardSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, PleosColors.EmeraldVoice.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "일행과 실시간 음성 연결됨",
                        color = PleosColors.TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (voiceCall.isHardBrakingMuted) "⚠️ 급제동 감지 · 내 마이크 일시 차단 중"
                        else if (voiceCall.isMicMuted) "마이크가 꺼져 있어 일행에게 들리지 않습니다."
                        else "내 음성이 일행에게 실시간으로 전달되고 있습니다.",
                        color = if (voiceCall.isHardBrakingMuted || voiceCall.isMicMuted) PleosColors.CoralDanger else PleosColors.TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = if (voiceCall.isHardBrakingMuted) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // 음성 안내 힌트 칩
            Surface(
                color = PleosColors.PleosCyan.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PleosColors.PleosCyan.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "💡 '오디, 마이크 꺼줘' 또는 '통화 끊어줘'",
                        color = PleosColors.PleosCyan,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 버튼 크기 대폭 확대 (높이 최소 64dp)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.5.dp,
                    if (voiceCall.isMicMuted) PleosColors.CoralDanger else PleosColors.BorderSubtle
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (voiceCall.isMicMuted) PleosColors.CoralDanger.copy(alpha = 0.12f) else PleosColors.CardSurface
                )
            ) {
                Icon(
                    imageVector = if (voiceCall.isMicMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = if (voiceCall.isMicMuted) PleosColors.CoralDanger else PleosColors.TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (voiceCall.isMicMuted) "마이크 꺼짐 (터치하여 켜기)" else "마이크 켜짐 (터치하여 끄기)",
                    color = PleosColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Button(
                onClick = onHangUpCall,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PleosColors.CoralDanger,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallEnd,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "통화 종료",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun StandbyVoiceAndSyncDock(
    safetyLevel: DrivingSafetyLevel,
    voiceCall: ActiveVoiceCallState,
    isDirectCall: Boolean,
    onToggleMute: () -> Unit,
    onHangUpCall: () -> Unit
) {
    if (voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED) {
        VoiceCallControlCard(
            voiceCall = voiceCall,
            isDirectCall = isDirectCall,
            onToggleMute = onToggleMute,
            onHangUpCall = onHangUpCall
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = PleosColors.PleosCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "AI 그룹 주행 안전 코파일럿",
                        color = PleosColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Surface(
                    color = PleosColors.CardSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, PleosColors.BorderSubtle)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "모든 동행 차량 정상 경로 유지 중",
                            color = PleosColors.EmeraldVoice,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "동행차 경로 이탈(80m+) 또는 동승석 네비게이터 우회 제안 발생 시, 현재 주행 안전 등급(${safetyLevel.name})에 맞춰 이 영역에 적응형 브리핑이 표시됩니다.",
                            color = PleosColors.TextSecondary,
                            fontSize = 17.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            Surface(
                color = PleosColors.CardSurfaceElevated,
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Gleo AI 음성 액션 연동 준비됨",
                        color = PleosColors.PleosCyan,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "하단 시뮬레이션 바에서 [시나리오 A] 또는 [시나리오 B]를 눌러 실시간 안전 적응형 오버레이를 즉시 테스트할 수 있습니다.",
                        color = PleosColors.TextSecondary,
                        fontSize = 16.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}
