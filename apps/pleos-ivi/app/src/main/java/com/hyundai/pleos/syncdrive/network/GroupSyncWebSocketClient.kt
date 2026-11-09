package com.hyundai.pleos.syncdrive.network

import android.util.Log
import com.hyundai.pleos.syncdrive.domain.AlertDecisionPayload
import com.hyundai.pleos.syncdrive.domain.GroupSessionSnapshot
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.MemberRole
import com.hyundai.pleos.syncdrive.domain.ServerWsEvent
import com.hyundai.pleos.syncdrive.domain.SessionEndReason
import com.hyundai.pleos.syncdrive.domain.SyncProtocolJson
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import com.hyundai.pleos.syncdrive.domain.clampTelemetryIntervalMs
import com.hyundai.pleos.syncdrive.domain.deriveDefaultTelemetryIntervalMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

enum class WsConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * OkHttp 기반 실시간 그룹 주행 동기화 WebSocket 클라이언트 (기본값: ws://10.0.2.2:4000/ws).
 *
 * - 연결 시 JOIN_GROUP 자동 송신
 * - 서버 TELEMETRY_ACK.nextIntervalMs (1000ms ~ 10000ms) 주기에 맞춘 적응형 TELEMETRY_UPDATE 루프 수행
 * - SESSION_SNAPSHOT, GROUP_ALERT, ALERT_RESOLVED, SESSION_TERMINATED 수신 및 상태 스트림 방출
 * - ALERT_DECISION 및 VOICE_STATE_CHANGE 즉시 송신 지원
 */
class GroupSyncWebSocketClient(
    private val scope: CoroutineScope,
    private val telemetryProvider: () -> LocalVehicleSnapshot,
    initialWsUrl: String = DEFAULT_EMULATOR_WS_URL,
    initialGroupId: String = DEFAULT_GROUP_ID,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {
    private val _wsUrl = MutableStateFlow(initialWsUrl)
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    private val _groupId = MutableStateFlow(initialGroupId)
    val groupId: StateFlow<String> = _groupId.asStateFlow()

    private val _connectionStatus = MutableStateFlow(WsConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<WsConnectionStatus> = _connectionStatus.asStateFlow()

    private val _telemetryIntervalMs = MutableStateFlow(5_000L)
    val telemetryIntervalMs: StateFlow<Long> = _telemetryIntervalMs.asStateFlow()

    private val _sessionSnapshot = MutableStateFlow<GroupSessionSnapshot?>(null)
    val sessionSnapshot: StateFlow<GroupSessionSnapshot?> = _sessionSnapshot.asStateFlow()

    private val _events = MutableSharedFlow<ServerWsEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ServerWsEvent> = _events.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var webSocket: WebSocket? = null
    private var telemetryLoopJob: Job? = null
    private var reconnectJob: Job? = null
    private var manualDisconnect: Boolean = false

    /**
     * 서버에서 LiveKit 토큰 발급 (POST /api/groups/:id/voice-token). 실패 시 null.
     * ws://host:4000/ws → http://host:4000
     */
    /** (livekitUrl, token). 서버 URL이 localhost면 차량에서 닿지 않으므로 호출부가 기본값으로 대체 */
    suspend fun fetchVoiceToken(roomName: String, memberId: String, displayName: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val base = _wsUrl.value.replaceFirst("ws", "http").substringBeforeLast("/ws")
            val body = org.json.JSONObject()
                .put("memberId", memberId).put("displayName", displayName).put("roomName", roomName)
                .toString().toRequestBody("application/json".toMediaType())
            val request = okhttp3.Request.Builder().url("$base/api/groups/${_groupId.value}/voice-token").post(body).build()
            runCatching {
                okHttpClient.newCall(request).execute().use { res ->
                    if (!res.isSuccessful) null else org.json.JSONObject(res.body!!.string()).let { j ->
                        val token = j.optString("token").ifEmpty { null } ?: return@let null
                        j.optString("livekitUrl") to token
                    }
                }
            }.onFailure { android.util.Log.w("GroupSyncWs", "voice token fetch failed: ${it.message}") }.getOrNull()
        }

    fun connect(url: String = _wsUrl.value, targetGroupId: String = _groupId.value) {
        manualDisconnect = false
        reconnectJob?.cancel()
        _wsUrl.value = url
        _groupId.value = targetGroupId

        webSocket?.cancel()
        _connectionStatus.value = WsConnectionStatus.CONNECTING

        val request = runCatching {
            Request.Builder().url(url).build()
        }.getOrElse { err ->
            _lastError.value = "잘못된 WebSocket URL: ${err.message}"
            _connectionStatus.value = WsConnectionStatus.DISCONNECTED
            return
        }

        webSocket = okHttpClient.newWebSocket(request, SocketListener())
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        telemetryLoopJob?.cancel()
        webSocket?.close(1000, "IVI client closing")
        webSocket = null
        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
    }

    fun pushImmediateTelemetry() {
        val ws = webSocket ?: return
        if (_connectionStatus.value != WsConnectionStatus.CONNECTED) return
        val snapshot = telemetryProvider()
        val payload = snapshot.toTelemetryPayload(groupId = _groupId.value)
        ws.send(SyncProtocolJson.encodeTelemetryUpdate(payload))
    }

    fun sendAlertDecision(eventId: String, decision: VoiceDecision): Boolean {
        val ws = webSocket ?: return false
        if (_connectionStatus.value != WsConnectionStatus.CONNECTED) return false
        val local = telemetryProvider()
        val payload = AlertDecisionPayload(
            groupId = _groupId.value,
            eventId = eventId,
            memberId = local.vehicleId,
            decision = decision
        )
        return ws.send(SyncProtocolJson.encodeAlertDecision(payload))
    }

    fun sendVoiceStateChange(inVoiceCall: Boolean): Boolean {
        val ws = webSocket ?: return false
        if (_connectionStatus.value != WsConnectionStatus.CONNECTED) return false
        val local = telemetryProvider()
        return ws.send(
            SyncProtocolJson.encodeVoiceStateChange(
                groupId = _groupId.value,
                memberId = local.vehicleId,
                inVoiceCall = inVoiceCall
            )
        )
    }

    /** decline=true: 받은 1:1 호출 거절(무응답 포함). toMemberId=원래 발신자 */
    fun sendVoiceInvite(toMemberId: String, roomName: String, decline: Boolean = false): Boolean {
        val ws = webSocket ?: return false
        if (_connectionStatus.value != WsConnectionStatus.CONNECTED) return false
        val local = telemetryProvider()
        return ws.send(
            SyncProtocolJson.encodeVoiceInvite(
                com.hyundai.pleos.syncdrive.domain.VoiceInviteWsEnvelope(
                    type = if (decline) "VOICE_DECLINE" else "VOICE_INVITE",
                    groupId = _groupId.value,
                    fromMemberId = local.vehicleId,
                    fromDisplayName = local.displayName,
                    toMemberId = toMemberId,
                    roomName = roomName
                )
            )
        )
    }

    fun sendEndGroupSession(reason: SessionEndReason): Boolean {
        val ws = webSocket ?: return false
        if (_connectionStatus.value != WsConnectionStatus.CONNECTED) return false
        return ws.send(
            SyncProtocolJson.encodeEndGroupSession(
                groupId = _groupId.value,
                reason = reason
            )
        )
    }

    internal fun handleRawServerMessage(rawText: String) {
        val parsed = SyncProtocolJson.decodeServerMessage(rawText) ?: return
        when (parsed) {
            is ServerWsEvent.SessionSnapshot -> {
                _sessionSnapshot.value = parsed.snapshot
                _groupId.value = parsed.snapshot.groupId
            }
            is ServerWsEvent.TelemetryAck -> {
                _telemetryIntervalMs.value = clampTelemetryIntervalMs(parsed.nextIntervalMs)
            }
            is ServerWsEvent.SessionTerminated -> {
                _sessionSnapshot.value = null
            }
            is ServerWsEvent.ErrorMessage -> {
                _lastError.value = parsed.message
            }
            is ServerWsEvent.GroupAlert,
            is ServerWsEvent.AlertResolved,
            is ServerWsEvent.ProposalRejected,
            is ServerWsEvent.VoiceInvite -> Unit
        }
        _events.tryEmit(parsed)
    }

    private fun startAdaptiveTelemetryLoop(ws: WebSocket) {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = scope.launch {
            while (isActive && _connectionStatus.value == WsConnectionStatus.CONNECTED) {
                val localSnapshot = telemetryProvider()
                val payload = localSnapshot.toTelemetryPayload(groupId = _groupId.value)
                ws.send(SyncProtocolJson.encodeTelemetryUpdate(payload))

                // 폴링 없이 대기: 서버가 주기를 바꾸면(예: 10s -> 1s) 즉시 깨어남
                withTimeoutOrNull(_telemetryIntervalMs.value) { _telemetryIntervalMs.drop(1).first() }
            }
        }
    }

    private fun scheduleReconnectIfNeeded() {
        if (manualDisconnect) return
        telemetryLoopJob?.cancel()
        _connectionStatus.value = WsConnectionStatus.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (!manualDisconnect) {
                connect(_wsUrl.value, _groupId.value)
            }
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionStatus.value = WsConnectionStatus.CONNECTED
            _lastError.value = null

            val local = telemetryProvider()
            _telemetryIntervalMs.value = deriveDefaultTelemetryIntervalMs(local.safetyLevel)

            val joinMsg = SyncProtocolJson.encodeJoinGroup(
                groupId = _groupId.value,
                memberId = local.vehicleId,
                displayName = local.displayName,
                role = MemberRole.PLEOS_HOST
            )
            webSocket.send(joinMsg)
            startAdaptiveTelemetryLoop(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleRawServerMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            telemetryLoopJob?.cancel()
            if (manualDisconnect) {
                _connectionStatus.value = WsConnectionStatus.DISCONNECTED
            } else {
                scheduleReconnectIfNeeded()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "WebSocket connection failed (${_wsUrl.value}): ${t.message}")
            _lastError.value = t.message
            scheduleReconnectIfNeeded()
        }
    }

    companion object {
        private const val TAG = "GroupSyncWsClient"
        const val DEFAULT_EMULATOR_WS_URL = "ws://10.0.2.2:4000/ws"
        const val DEFAULT_GROUP_ID = "GRP-PLEOS-DEMO"
        private const val RECONNECT_DELAY_MS = 3_500L

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
