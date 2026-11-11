package com.hyundai.pleos.syncdrive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.hyundai.pleos.syncdrive.sdk.GleoIntentEventBus
import com.hyundai.pleos.syncdrive.ui.MainDashboardScreen
import com.hyundai.pleos.syncdrive.domain.DrivingSafetyLevel
import com.hyundai.pleos.syncdrive.ui.SyncDriveViewModel
import com.hyundai.pleos.syncdrive.ui.theme.PleosSyncDriveTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SyncDriveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeAlertsForForeground()

        val prefs = getSharedPreferences("odee", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONSENT, false)) startSession()

        setContent {
            PleosSyncDriveTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var consented by remember { mutableStateOf(prefs.getBoolean(KEY_CONSENT, false)) }

                // Pleos 개인정보 가이드: 위치·음성 요청 목적 고지 + 간단한 동의
                if (!consented) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("오디가 위치와 음성을 사용합니다") },
                        text = {
                            Text(
                                "• 위치·배터리: 그룹 일행에게 내 차 위치와 이탈 사유를 알리기 위해 사용\n" +
                                    "• 마이크: 운전 중 음성 명령과 그룹 통화에 사용\n" +
                                    "• 기록은 서버 메모리에만 있고 목적지 도착·그룹 종료 시 즉시 삭제"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                prefs.edit().putBoolean(KEY_CONSENT, true).apply()
                                consented = true
                                startSession()
                            }) { Text("동의하고 시작") }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text("나가기") }
                        }
                    )
                }

                MainDashboardScreen(
                    state = state,
                    onSelectStationary = viewModel::selectStationaryParkPreset,
                    onSelectUrbanDriving = viewModel::selectUrbanDrivingPreset,
                    onSelectHighwayFocus = viewModel::selectHighwayFocusPreset,
                    onToggleBraking = viewModel::toggleBrakingSimulation,
                    onStepSoc = viewModel::stepSocPercent,
                    onTriggerScenarioA = viewModel::triggerDemoScenarioADeviation,
                    onTriggerScenarioB = viewModel::triggerDemoScenarioBProposal,
                    onAlertDecision = viewModel::onAlertDecisionSelected,
                    onToggleMute = viewModel::toggleMicMute,
                    onHangUpCall = viewModel::hangUpVoiceCall,
                    onReconnectWs = viewModel::reconnectWebSocket,
                    onGroupCall = viewModel::startGroupCall,
                    onDirectCall = viewModel::startDirectCall
                )
            }
        }
    }

    private fun startSession() {
        // 동의 고지만 하고 런타임 권한을 요청하지 않아 그룹 통화가 마이크를 못 열었음(AudioRecord 실패, 실측)
        val missing = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        viewModel.startGroupSession()
        OdeeKeepAliveService.start(this)
    }

    /**
     * B안: 평소엔 순정 내비가 앞. 정차 중 일행 알림이 오면 오디를 앞으로(주행 중엔 음성+알림만), 결정·타임아웃으로 알림이 닫히면
     * 원래 화면(순정 내비)으로 되돌린다. 사용자가 직접 연 경우엔 되돌리지 않음.
     */
    private fun observeAlertsForForeground() {
        lifecycleScope.launch {
            viewModel.uiState.map { it.activeAlert }.distinctUntilChangedBy { it?.eventId }.collect { alert ->
                if (alert != null) {
                    val stationary = viewModel.uiState.value.safetyLevel == DrivingSafetyLevel.STATIONARY
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !stationary) {
                        // 안전 우선: 주행 중엔 지도를 가리지 않는다. 음성 브리핑·음성 응답 + 알림만
                        OdeeKeepAliveService.notifyAlert(this@MainActivity, alert.headlineKo, fullScreen = false)
                    } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        broughtToFront = true
                        OdeeKeepAliveService.notifyAlert(this@MainActivity, alert.headlineKo)
                        runCatching {
                            startActivity(
                                Intent(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        }
                    }
                } else {
                    OdeeKeepAliveService.clearAlert(this@MainActivity)
                    returnToNaviIfBroughtToFront()
                }
            }
        }
    }

    private fun returnToNaviIfBroughtToFront() {
        if (!broughtToFront) return
        broughtToFront = false
        moveTaskToBack(true)
        viewModel.showNavigationScreen()
    }

    private var broughtToFront = false

    private companion object {
        const val KEY_CONSENT = "consent_location_voice_v1"
    }
}
