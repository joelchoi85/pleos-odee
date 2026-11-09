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
import com.hyundai.pleos.syncdrive.ui.SyncDriveViewModel
import com.hyundai.pleos.syncdrive.ui.theme.PleosSyncDriveTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SyncDriveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleGleoIntent(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGleoIntent(intent)
    }

    private fun startSession() {
        viewModel.startGroupSession()
        OdeeKeepAliveService.start(this)
    }

    /**
     * B안: 평소엔 순정 내비가 앞. 일행 알림이 오면 오디를 앞으로, 결정·타임아웃으로 알림이 닫히면
     * 원래 화면(순정 내비)으로 되돌린다. 사용자가 직접 연 경우엔 되돌리지 않음.
     */
    private fun observeAlertsForForeground() {
        lifecycleScope.launch {
            viewModel.uiState.map { it.activeAlert }.distinctUntilChangedBy { it?.eventId }.collect { alert ->
                if (alert != null) {
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
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

    /** Gleo "일행 어디쯤이야?" → SHOW_GROUP_LOCATION. 화면을 띄우고 요약을 음성 응답으로 돌려준 뒤 잠시 후 내비 복귀 */
    private fun handleGleoIntent(incoming: Intent?) {
        if (incoming?.action != GleoIntentEventBus.ACTION_SHOW_GROUP_LOCATION) return
        val others = viewModel.uiState.value.companions.filterNot { it.isSelfHost }
        val summary = if (others.isEmpty()) "아직 연결된 일행이 없습니다." else others.joinToString(" ") {
            "${it.displayName} 도착 ${it.etaDisplay}${if (it.isOffRoute) ", 경로 이탈 중" else ""}."
        }
        GleoIntentEventBus.respond(this, GleoIntentEventBus.ACTION_SHOW_GROUP_LOCATION, true, summary)
        broughtToFront = true
        lifecycleScope.launch {
            delay(GLEO_GLANCE_MS)
            if (viewModel.uiState.value.activeAlert == null) returnToNaviIfBroughtToFront()
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
        const val GLEO_GLANCE_MS = 10_000L
    }
}
