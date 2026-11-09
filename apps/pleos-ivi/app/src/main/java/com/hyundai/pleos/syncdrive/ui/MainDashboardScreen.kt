package com.hyundai.pleos.syncdrive.ui

import com.hyundai.pleos.syncdrive.ui.map.ConvoyVerticalLine
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import com.hyundai.pleos.syncdrive.BuildConfig
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import com.hyundai.pleos.syncdrive.audio.VoiceRoomState
import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.CompanionVehicleUiModel
import com.hyundai.pleos.syncdrive.domain.DrivingSafetyLevel
import com.hyundai.pleos.syncdrive.domain.GearState
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.RecommendedWaypoint
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import com.hyundai.pleos.syncdrive.network.WsConnectionStatus
import com.hyundai.pleos.syncdrive.ui.theme.PleosColors
import kotlin.math.roundToInt

@Composable
fun MainDashboardScreen(
    state: SyncDriveScreenState,
    onSelectStationary: () -> Unit,
    onSelectUrbanDriving: () -> Unit,
    onSelectHighwayFocus: () -> Unit,
    onToggleBraking: () -> Unit,
    onStepSoc: (Double) -> Unit,
    onTriggerScenarioA: () -> Unit,
    onTriggerScenarioB: () -> Unit,
    onAlertDecision: (VoiceDecision) -> Unit,
    onToggleMute: () -> Unit,
    onHangUpCall: () -> Unit,
    onReconnectWs: () -> Unit,
    onGroupCall: () -> Unit,
    onDirectCall: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ambientTargetColor = when (state.ambientColor) {
        AmbientAlertColor.NONE -> Color.Transparent
        AmbientAlertColor.AMBER_DEVIATION -> PleosColors.AmberAlert
        AmbientAlertColor.CYAN_PROPOSAL -> PleosColors.PleosCyan
        AmbientAlertColor.GREEN_VOICE_ACTIVE -> PleosColors.EmeraldVoice
    }

    val animatedAmbientColor by animateColorAsState(
        targetValue = ambientTargetColor,
        animationSpec = tween(400),
        label = "ambientGlowColor"
    )

    // Pleos 주행 안전: 주행 중 반복 애니메이션 금지 → 정차 중에만 펄스
    val glowAlpha = if (state.localVehicle.safetyLevel == DrivingSafetyLevel.STATIONARY && state.ambientColor != AmbientAlertColor.NONE) {
        val pulseTransition = rememberInfiniteTransition(label = "ambientPulse")
        pulseTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100),
                repeatMode = RepeatMode.Reverse
            ),
            label = "ambientGlowAlpha"
        ).value
    } else {
        0.9f
    }

    val perimeterBorderColor = if (state.ambientColor == AmbientAlertColor.NONE) {
        PleosColors.BorderSubtle.copy(alpha = 0.45f)
    } else {
        animatedAmbientColor.copy(alpha = glowAlpha)
    }

    var showDevBar by remember { mutableStateOf(false) }
    val showOverlay = state.activeAlert != null || state.voiceCall.roomState != VoiceRoomState.DISCONNECTED

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(PleosColors.ObsidianBg)
            .border(
                width = if (state.ambientColor == AmbientAlertColor.NONE) 1.dp else 5.dp,
                color = perimeterBorderColor
            )
    ) {
        // 메인 영역: 알림 > DEV > 그룹 현황
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                showOverlay -> SafetyAdaptiveOverlayPanel(
                    safetyLevel = state.safetyLevel,
                    activeAlert = state.activeAlert,
                    countdown = state.countdown,
                    voiceCall = state.voiceCall,
                    isVoiceListening = state.isVoiceCommandListening,
                    onDecision = onAlertDecision,
                    onToggleMute = onToggleMute,
                    onHangUpCall = onHangUpCall,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    isHardBrakingAudioMuted = state.isHardBrakingAudioMuted,
                    // 그룹 방 = ROOM-<초대코드>, 1:1 방 = 그 뒤에 -<두 ID>
                    isDirectCall = state.voiceCall.roomName.let { it != null && it != "ROOM-${state.inviteCode}" }
                )
                showDevBar -> Box(Modifier.padding(12.dp)) {
                    DeveloperQuickSimulationBar(
                        localVehicle = state.localVehicle,
                        safetyLevel = state.safetyLevel,
                        onSelectStationary = onSelectStationary,
                        onSelectUrbanDriving = onSelectUrbanDriving,
                        onSelectHighwayFocus = onSelectHighwayFocus,
                        onToggleBraking = onToggleBraking,
                        onStepSoc = onStepSoc,
                        onTriggerScenarioA = onTriggerScenarioA,
                        onTriggerScenarioB = onTriggerScenarioB
                    )
                }
                // Pleos 주의 분산 기준: 주행 중엔 일행별 1줄(이름·도착·이탈)만 큰 글씨로
                state.safetyLevel != DrivingSafetyLevel.STATIONARY -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
                ) {
                    if (!state.stationaryBriefing.isNullOrBlank()) {
                        Surface(
                            color = PleosColors.PanelSurface,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.5.dp, PleosColors.PleosCyan.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = state.stationaryBriefing,
                                color = PleosColors.TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
                            )
                        }
                    }
                    state.companions.filterNot { it.isSelfHost }.forEach { c ->
                        Text(
                            "${c.displayName}  ${c.etaDisplay}${if (c.isOffRoute) "  · 이탈" else ""}",
                            color = if (c.isOffRoute) PleosColors.AmberAlert else PleosColors.TextPrimary,
                            fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // ponytail: 화면 밀도 160dpi에서 글자가 작아 이 영역만 2배. 전체 타이포 정리 시 개별 sp로 교체
                else -> CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, LocalDensity.current.fontScale * 2f)
                ) {
                    Box(Modifier.padding(horizontal = 32.dp, vertical = 28.dp)) {
                        StationaryGroupDashboardContent(
                            inviteCode = state.inviteCode,
                            originName = state.originName,
                            destinationName = state.destinationName,
                            companions = state.companions,
                            waypoints = state.waypoints,
                            onGroupCall = onGroupCall,
                            onDirectCall = onDirectCall,
                            stationaryBriefing = state.stationaryBriefing
                        )
                    }
                }
            }

            // 급제동 시 최상단 안전 경고 배너 오버레이
            if (state.isHardBrakingAudioMuted && state.voiceCall.roomState == VoiceRoomState.CONNECTED_DUCKED) {
                Surface(
                    color = PleosColors.CoralDanger,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp)
                        .zIndex(10f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "⚠️ [안전 우선] 급제동 감지 · 내 마이크 일시 차단",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        // 고정 영역 (오른쪽 180dp): 도착 → 세로 대열선 → 출발
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val connected = state.wsStatus == WsConnectionStatus.CONNECTED
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !connected, onClick = onReconnectWs)) {
                Box(Modifier.size(12.dp).background(if (connected) PleosColors.EmeraldVoice else PleosColors.AmberAlert, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(state.localVehicle.etaIso.substringAfter('T').take(5), color = PleosColors.TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                state.destinationName, color = PleosColors.TextSecondary, fontSize = 22.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
            ConvoyVerticalLine(
                route = state.routePolyline,
                vehicles = state.companions,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            Text(
                state.originName, color = PleosColors.TextMuted, fontSize = 20.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            if (BuildConfig.DEBUG) {
                TextButton(onClick = { showDevBar = !showDevBar }) {
                    Text(if (showDevBar) "DEV ◂" else "DEV ▸", color = PleosColors.TextMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun StationaryGroupDashboardContent(
    inviteCode: String,
    originName: String,
    destinationName: String,
    companions: List<CompanionVehicleUiModel>,
    waypoints: List<RecommendedWaypoint>,
    onGroupCall: () -> Unit,
    onDirectCall: (String) -> Unit,
    stationaryBriefing: String? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (!stationaryBriefing.isNullOrBlank()) {
            item {
                Surface(
                    color = PleosColors.PanelSurface,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.5.dp, PleosColors.PleosCyan.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stationaryBriefing,
                            color = PleosColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = onGroupCall,
                modifier = Modifier.fillMaxWidth().height(88.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PleosColors.EmeraldVoice)
            ) {
                Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text("그룹 통화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 6-Digit Invite Code & Companion Mobile Navigator PWA Link Card
        item {
            Surface(
                color = PleosColors.PanelSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, PleosColors.PleosCyan.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = PleosColors.PleosCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCode2,
                                contentDescription = null,
                                tint = PleosColors.PleosCyan,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(30.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "동승석 네비게이터 · 타사 차량 초대 코드",
                                color = PleosColors.TextSecondary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                Text(
                                    text = inviteCode,
                                    color = PleosColors.PleosCyan,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black
                                )
                                Text(
                                    text = "http://localhost:5173/?code=$inviteCode",
                                    color = PleosColors.TextPrimary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Surface(
                        color = PleosColors.CardSurfaceElevated,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "목적지 도착 시 휘발성 자동 파기",
                            color = PleosColors.EmeraldVoice,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // All 2~4 Group Vehicles Telemetry Cards (2x2 Grid rows)
        val chunkedCompanions = companions.chunked(2)
        items(chunkedCompanions) { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                rowItems.forEach { vehicle ->
                    CompanionVehicleCard(
                        vehicle = vehicle,
                        onCall = if (vehicle.isSelfHost) null else ({ onDirectCall(vehicle.memberId) }),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Active Group Route Waypoints List
        item {
            Surface(
                color = PleosColors.PanelSurface,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, PleosColors.BorderSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "그룹 동기화 경로 및 경유지 ($originName → $destinationName)",
                            color = PleosColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "경유지 ${waypoints.size}곳",
                            color = PleosColors.PleosCyan,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    waypoints.forEachIndexed { index, wp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PleosColors.CardSurface)
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    imageVector = if (index == waypoints.lastIndex) {
                                        Icons.Rounded.Flag
                                    } else {
                                        Icons.Rounded.EvStation
                                    },
                                    contentDescription = null,
                                    tint = PleosColors.PleosCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "${index + 1}. ${wp.name}",
                                    color = PleosColors.TextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = String.format("%.4f, %.4f", wp.lat, wp.lng),
                                color = PleosColors.TextMuted,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionVehicleCard(
    vehicle: CompanionVehicleUiModel,
    onCall: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        vehicle.isOffRoute -> PleosColors.AmberAlert
        vehicle.inVoiceCall -> PleosColors.EmeraldVoice
        vehicle.isSelfHost -> PleosColors.PleosCyan.copy(alpha = 0.6f)
        else -> PleosColors.BorderSubtle
    }

    val lowEnergy = vehicle.energyPercent <= 22

    Surface(
        modifier = modifier,
        color = PleosColors.PanelSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (vehicle.isOffRoute) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DirectionsCar,
                        contentDescription = null,
                        tint = if (vehicle.isSelfHost) PleosColors.PleosCyan else PleosColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = vehicle.displayName,
                        color = PleosColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (vehicle.isOffRoute) {
                    Surface(
                        color = PleosColors.AmberAlert.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, PleosColors.AmberAlert)
                    ) {
                        Text(
                            text = "이탈 ${vehicle.crossTrackDistanceMeters}m",
                            color = PleosColors.AmberAlert,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                } else {
                    Surface(
                        color = PleosColors.CardSurfaceElevated,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = vehicle.roleLabelKo,
                            color = PleosColors.TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // 왼쪽(운전석 쪽)에 두 줄 높이의 통화 버튼(내 차 제외) + 기어·속도·도착 / 배터리 두 줄
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onCall != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(PleosColors.EmeraldVoice)
                            .clickable(onClickLabel = "${vehicle.displayName}와 통화", onClick = onCall),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Call,
                            contentDescription = "${vehicle.displayName}와 통화",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${vehicle.gear.name}단 · ${vehicle.speedKmh} km/h · ${vehicle.etaDisplay}",
                        color = PleosColors.TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 배터리 = 막대 하나: 채움 비율 + 안쪽에 "82% · 340 km"
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PleosColors.CardSurfaceElevated),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((vehicle.energyPercent / 100f).coerceIn(0f, 1f))
                                .background((if (lowEnergy) PleosColors.AmberAlert else PleosColors.EmeraldVoice).copy(alpha = 0.55f))
                        )
                        Text(
                            text = "${vehicle.energyPercent}%${vehicle.remainingRangeKm?.let { " · $it km" }.orEmpty()}",
                            color = PleosColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeveloperQuickSimulationBar(
    localVehicle: LocalVehicleSnapshot,
    safetyLevel: DrivingSafetyLevel,
    onSelectStationary: () -> Unit,
    onSelectUrbanDriving: () -> Unit,
    onSelectHighwayFocus: () -> Unit,
    onToggleBraking: () -> Unit,
    onStepSoc: (Double) -> Unit,
    onTriggerScenarioA: () -> Unit,
    onTriggerScenarioB: () -> Unit
) {
    Surface(
        color = PleosColors.PanelSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PleosColors.BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SimChipButton(
                label = "P단 정차(0km/h)",
                selected = safetyLevel == DrivingSafetyLevel.STATIONARY,
                activeColor = PleosColors.EmeraldVoice,
                onClick = onSelectStationary,
                modifier = Modifier.weight(1.05f)
            )
            SimChipButton(
                label = "도심(45km/h)",
                selected = localVehicle.gear == GearState.D &&
                    localVehicle.speedKmh in 2.0..59.0 &&
                    !localVehicle.isBraking,
                activeColor = PleosColors.PleosCyan,
                onClick = onSelectUrbanDriving,
                modifier = Modifier.weight(0.95f)
            )
            SimChipButton(
                label = "고속(90km/h)",
                selected = localVehicle.speedKmh >= 60.0,
                activeColor = PleosColors.CoralDanger,
                onClick = onSelectHighwayFocus,
                modifier = Modifier.weight(0.95f)
            )
            SimChipButton(
                label = if (localVehicle.isBraking) "제동 ON" else "브레이크",
                selected = localVehicle.isBraking,
                activeColor = PleosColors.CoralDanger,
                onClick = onToggleBraking,
                modifier = Modifier.weight(0.75f)
            )
            SimChipButton(
                label = "SOC -10%",
                selected = false,
                activeColor = PleosColors.AmberAlert,
                onClick = { onStepSoc(-10.0) },
                modifier = Modifier.weight(0.75f)
            )
            SimChipButton(
                label = "시나리오A(+5분)",
                selected = false,
                activeColor = PleosColors.AmberAlert,
                onClick = onTriggerScenarioA,
                modifier = Modifier.weight(1.05f)
            )
            SimChipButton(
                label = "시나리오B(-8분)",
                selected = false,
                activeColor = PleosColors.PleosCyan,
                onClick = onTriggerScenarioB,
                modifier = Modifier.weight(1.05f)
            )
        }
    }
}

@Composable
private fun SimChipButton(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 42.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) activeColor else PleosColors.CardSurfaceElevated,
            contentColor = if (selected) Color(0xFF04131B) else PleosColors.TextPrimary
        )
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
