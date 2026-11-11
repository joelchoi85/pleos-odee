package com.hyundai.pleos.syncdrive.sdk

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.hyundai.pleos.syncdrive.BuildConfig
import com.hyundai.pleos.syncdrive.domain.AmbientAlertColor
import com.hyundai.pleos.syncdrive.domain.GearState
import com.hyundai.pleos.syncdrive.domain.LocalVehicleSnapshot
import com.hyundai.pleos.syncdrive.domain.VoiceDecision
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener as SttServerListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener as TtsServerListener

/**
 * Pleos Connect SDK 게이트웨이 (Vehicle · FusedLocation · NaviHelper · TTS · STT).
 *
 * 각 SDK는 Pleos 에뮬레이터/실차의 서비스에 바인딩되며, 서비스가 없으면(일반 AAOS 에뮬레이터)
 * 조용히 비활성화된다. TTS/STT는 Pleos 실패 시 Android 기본 엔진으로 폴백.
 * 차량 값 변경은 [vehicleUpdates]로 내보내 ViewModel이 시뮬레이터 스냅샷에 병합한다.
 */
class PleosNativeGatewayImpl(
    context: Context,
    initialSnapshot: LocalVehicleSnapshot = LocalVehicleSnapshot()
) : VehiclePlatformGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _telemetryFlow = MutableStateFlow(initialSnapshot)
    override val telemetryFlow: StateFlow<LocalVehicleSnapshot> = _telemetryFlow.asStateFlow()

    private val _ambientColorFlow = MutableStateFlow(AmbientAlertColor.NONE)
    override val ambientColorFlow: StateFlow<AmbientAlertColor> = _ambientColorFlow.asStateFlow()

    private val _vehicleUpdates =
        MutableSharedFlow<(LocalVehicleSnapshot) -> LocalVehicleSnapshot>(extraBufferCapacity = 64)
    val vehicleUpdates: SharedFlow<(LocalVehicleSnapshot) -> LocalVehicleSnapshot> = _vehicleUpdates.asSharedFlow()

    private val hasCredentials = BuildConfig.PLEOS_CLIENT_ID.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    // --- Vehicle SDK ---
    private val vehicle: Vehicle? = sdkOrNull("Vehicle") { Vehicle(appContext).also { it.initialize() } }
    val isPleosSdkBound: Boolean get() = vehicle != null
    private var originalAmbientRgb: Int? = null
    @Volatile private var ambientArea: SeatArea? = null
    private val ambientHistory = ArrayDeque<AmbientAlertColor>()

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onFailed(e: Exception) = logVehicleError(e)
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            value?.let { v -> emit { it.copy(socPercent = v.toDouble()) } }
        }
    }
    private val fuelListener = object : FuelPercentListener {
        override fun onFailed(e: Exception) = logVehicleError(e)
        override fun onFuelPercentUpdated(value: Int?) {
            value?.let { v -> emit { it.copy(fuelPercent = v.toDouble()) } }
        }
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onFailed(e: Exception) = logVehicleError(e)
        override fun onBrakePedalStatusUpdated(value: Boolean?) {
            value?.let { v -> emit { it.copy(isBraking = v) } }
        }
    }
    private val gearListener = object : GearSelectionListener {
        override fun onFailed(e: Exception) = logVehicleError(e)
        override fun onSelected(gear: VehicleGear?) {
            gear?.let { g -> emit { it.copy(gear = g.toGearState()) } }
        }
    }
    // Pleos RANGE_REMAINING 단위는 m (에뮬레이터 실측 500000.0 = 500km)
    private val rangeListener = object : RangeRemainingListener {
        override fun onFailed(e: Exception) = logVehicleError(e)
        override fun onRangeRemainingChanged(value: Float?) {
            value?.let { v -> emit { it.copy(remainingRangeKm = v / 1000.0) } }
        }
    }

    // --- FusedLocation SDK: 위치·방위만 (속도는 Car API) ---
    private val fusedLocation: FusedLocation? = sdkOrNull("FusedLocation") {
        FusedLocation(appContext).takeIf { it.isSupported() }?.also { it.initialize() }
    }
    private var lastFix: LocationData? = null

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            if (data == null) return
            val prev = lastFix
            // 서비스는 위치 변화가 없어도 1Hz로 같은 좌표를 보냄 → 2m 미만 변화는 무시
            if (prev != null && haversineMeters(prev.latitude, prev.longitude, data.latitude, data.longitude) < 2.0) return
            lastFix = data
            emit { it.copy(lat = data.latitude, lng = data.longitude, heading = data.heading.toDouble().coerceIn(0.0, 360.0)) }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "FusedLocation error: ${e.message}")
        }
    }

    // --- AAOS Car API: 표준 속도(PERF_VEHICLE_SPEED, 클러스터와 동일) + 주행 규제 ---
    private val car: Car? = sdkOrNull("Car") { Car.createCar(appContext) }
    private val carProperty: CarPropertyManager? =
        sdkOrNull("CarProperty") { car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
    private val uxManager: CarUxRestrictionsManager? =
        sdkOrNull("CarUxRestrictions") { car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            emit { it.copy(speedKmh = (mps * 3.6).coerceAtLeast(0.0)) }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "Speed property error zone=$zone")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r ->
        emit { it.copy(isUxRestricted = r.isRequiresDistractionOptimization) }
    }

    // --- NaviHelper SDK ---
    private val naviHelper: NaviHelper? = sdkOrNull("NaviHelper") { NaviHelper(appContext).also { it.initialize() } }

    // --- TTS: Pleos 우선, Android 폴백 ---
    private val pleosTts: PleosTts? = sdkOrNull("TextToSpeech") {
        PleosTts(appContext, if (hasCredentials) TtsMode.HYBRID else TtsMode.ON_DEVICE)
    }
    @Volatile private var isPleosTtsReady = false
    @Volatile private var pendingTtsDone: (() -> Unit)? = null

    // 오디 브리핑 = 순정 내비 안내와 같은 용도·음량 그룹. 포커스를 잡아 안내끼리 겹치지 않게(음악은 감쇄)
    private val guidanceAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val audioManager by lazy { appContext.getSystemService(AudioManager::class.java) }
    private val guidanceFocus by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(guidanceAttributes)
            .build()
    }

    private val ttsEvents = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = finishTts()
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = finishTts()
        override fun onStop() = finishTts()
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private var androidTts: TextToSpeech? = null
    @Volatile private var isAndroidTtsReady = false

    // --- STT: Pleos 우선, Android 폴백 ---
    private val pleosStt: SpeechToText? = sdkOrNull("SpeechToText") {
        SpeechToText(appContext, if (hasCredentials) SttMode.HYBRID else SttMode.ON_DEVICE)
    }
    @Volatile private var isPleosSttReady = false

    private val sttResults = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            val decision = VoiceCommandParser.parse(stt) ?: return
            runCatching { pleosStt?.stop() }
            dispatchVoice(decision)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
            Log.w(TAG, "Pleos STT error: $errorType")
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private var speechRecognizer: SpeechRecognizer? = null
    @Volatile private var activeVoiceCallback: ((VoiceDecision) -> Unit)? = null

    init {
        bindVehicleListeners()
        bindCarApi()
        // initialize() 직후 등록하면 서비스 바인딩 전이라 콜백이 버려짐 (에뮬레이터 실측)
        // ponytail: 고정 지연. 연결 콜백 API가 생기면 그쪽으로 교체
        fusedLocation?.let { loc ->
            mainHandler.postDelayed({ runCatching { loc.registerFusedLocationCallback(locationListener); Log.i(TAG, "fused registered supported=${loc.isSupported()}") }.onFailure { Log.w(TAG, "fused register failed", it) } }, 1_500L)
        }
        initPleosTts()
        initPleosStt()
    }

    private fun bindVehicleListeners() {
        val v = vehicle ?: return
        runCatching { v.getEvBattery().registerEvBatteryStateOfCharge(socListener) }
        runCatching { v.getFuel().registerFuelPercent(fuelListener) }
        runCatching { v.getBrake().registerBrakePedalStatus(brakeListener) }
        runCatching { v.getGear().registerGearSelection(gearListener) }
        runCatching { v.getOdometer().registerRangeRemainingListener(rangeListener) }
        // 앰비언트 조명 지원 영역은 차종마다 다르므로 capability로 조회
        runCatching {
            v.getLight().checkAmbientLightBrightnessColorCapabilities({ _, areas ->
                val area = areas.firstOrNull() ?: return@checkAmbientLightBrightnessColorCapabilities
                ambientArea = area
                Log.i(TAG, "Ambient areas: ${areas.map { it.values.toList() }}")
                v.getLight().getAmbientLightBrightnessColor(area, { originalAmbientRgb = it }, ::logVehicleError)
            }, ::logVehicleError)
        }
    }

    private fun bindCarApi() {
        runCatching {
            carProperty?.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { Log.w(TAG, "Speed subscribe failed: ${it.message}") }
        uxManager?.let { m ->
            runCatching {
                m.registerListener(uxListener)
                uxListener.onUxRestrictionsChanged(m.currentCarUxRestrictions)
            }.onFailure { Log.w(TAG, "UxRestrictions subscribe failed: ${it.message}") }
        }
    }

    private fun initPleosTts() {
        val tts = pleosTts ?: return initAndroidTts()
        runCatching {
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    tts.addEventListener(ttsEvents)
                    tts.setLocale(Locale.KOREA)
                    runCatching { tts.setAudioAttributes(guidanceAttributes) }
                    if (!hasCredentials) {
                        isPleosTtsReady = true
                        return
                    }
                    tts.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET,
                        object : TtsServerListener {
                            override fun onConnected() { isPleosTtsReady = true }
                            override fun onFailed(errorType: ai.pleos.playground.tts.constant.ErrorType) {
                                Log.w(TAG, "Pleos TTS registerApp failed: $errorType")
                                mainHandler.post { initAndroidTts() }
                            }
                        })
                }

                override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) {
                    Log.w(TAG, "Pleos TTS init failed: $errorType")
                    mainHandler.post { initAndroidTts() }
                }
            })
        }.onFailure { initAndroidTts() }
    }

    private fun initPleosStt() {
        val stt = pleosStt ?: return
        runCatching {
            stt.initialize(object : SttInitListener {
                override fun onSuccess() {
                    stt.addListener(sttResults)
                    stt.setLocale(Locale.KOREA)
                    if (!hasCredentials) {
                        isPleosSttReady = true
                        return
                    }
                    stt.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET,
                        object : SttServerListener {
                            override fun onConnected() { isPleosSttReady = true }
                            override fun onFailed(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                                Log.w(TAG, "Pleos STT registerApp failed: $errorType")
                            }
                        })
                }

                override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) {
                    Log.w(TAG, "Pleos STT init failed: $errorType")
                }
            })
        }
    }

    private fun initAndroidTts() {
        if (androidTts != null) return
        runCatching {
            androidTts = TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val result = androidTts?.setLanguage(Locale.KOREAN)
                isAndroidTtsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                androidTts?.setSpeechRate(1.03f)
                androidTts?.setAudioAttributes(guidanceAttributes)
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = finishTts()
                    @Deprecated("Deprecated in Android SDK")
                    override fun onError(utteranceId: String?) = finishTts()
                    override fun onError(utteranceId: String?, errorCode: Int) = finishTts()
                })
            }
        }.onFailure { e -> Log.w(TAG, "Android TTS init skipped: ${e.message}") }
    }

    /** 순정 내비를 앞으로. NaviHelper 없으면 false → 호출 측이 moveTaskToBack 으로 폴백 */
    fun showNavigationScreen(): Boolean {
        val helper = naviHelper ?: return false
        return runCatching { helper.requestNavigationScreen(NavigationScreenRequest.Foreground) }
            .onFailure { Log.w(TAG, "NaviHelper.requestNavigationScreen failed: ${it.message}") }.isSuccess
    }

    fun setNavigationDestination(lat: Double, lng: Double, name: String) {
        val helper = naviHelper ?: return
        // cancelRoute()를 앞에 두면 순정 내비 cancelTrip이 끝나지 않아 '안내 종료'가 먹지 않음(실측) → 쓰지 않음.
        // 호출은 앱 프로세스당 목적지가 바뀔 때만(ViewModel distinctUntilChanged)
        odeeWaypointAdded = false
        runCatching {
            helper.requestRoute(RouteInfo(lat, lng, null, null, name, "", name, "0", RouteOption.RECOMMENDED, "", false))
        }.onFailure { Log.w(TAG, "NaviHelper.requestRoute failed: ${it.message}") }
    }

    /** 오디가 넣은 경유지가 내비에 남아 있는지. 새 그룹 경유지는 이전 것을 대체(쌓이면 "경유지는 최대 2개" 경고, 실측) */
    private var odeeWaypointAdded = false

    override suspend fun updateNavigationWaypoint(lat: Double, lng: Double, title: String): Boolean {
        val helper = naviHelper ?: return false
        if (odeeWaypointAdded) runCatching { helper.removeWaypoint(WaypointIndex.FIRST) }
        return runCatching {
            helper.addWaypoint(
                RequestWaypointInfo(
                    latitude = lat,
                    longitude = lng,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    poiName = title,
                    poiSubId = "0",
                    address = title
                )
            )
        }.onFailure { Log.w(TAG, "NaviHelper.addWaypoint failed: ${it.message}") }.isSuccess
            .also { if (it) odeeWaypointAdded = true }
    }

    override suspend fun setAmbientAlertState(alertColor: AmbientAlertColor) {
        val current = _ambientColorFlow.value
        if (current != alertColor) ambientHistory.addLast(current)
        _ambientColorFlow.value = alertColor
        applyAmbient(alertColor)
    }

    override suspend fun restoreAmbientState() {
        val restored = ambientHistory.removeLastOrNull() ?: AmbientAlertColor.NONE
        _ambientColorFlow.value = restored
        applyAmbient(restored)
    }

    // ponytail: 색상 int를 0xRRGGBB로 가정. 에뮬레이터에서 색이 다르면 ARGB로 변경
    private fun applyAmbient(color: AmbientAlertColor) {
        val light = vehicle?.getLight() ?: return
        val area = ambientArea ?: return
        val rgb = when (color) {
            AmbientAlertColor.NONE -> originalAmbientRgb ?: return
            AmbientAlertColor.AMBER_DEVIATION -> 0xFFB020
            AmbientAlertColor.CYAN_PROPOSAL -> 0x00D2FF
            AmbientAlertColor.GREEN_VOICE_ACTIVE -> 0x10B981
        }
        runCatching {
            light.setAmbientLightBrightnessColor(area, rgb, { a, c -> Log.i(TAG, "Ambient set $a -> 0x${Integer.toHexString(c)}") },
                { e -> Log.w(TAG, "Ambient color failed: ${e.message}") })
        }
    }

    override fun speakBriefing(text: String, onDone: () -> Unit) {
        pendingTtsDone = onDone
        runCatching { audioManager.requestAudioFocus(guidanceFocus) }
        val pleos = pleosTts
        if (isPleosTtsReady && pleos != null && runCatching { pleos.speak(text, true) }.isSuccess) {
            watchGuidancePlayback(onDone)
            return
        }

        val engine = androidTts
        if (!isAndroidTtsReady || engine == null ||
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "odee-${UUID.randomUUID()}") == TextToSpeech.ERROR
        ) {
            finishTts()
        }
    }

    /**
     * Pleos TTS가 온디바이스 재시도 경로에서 onDone을 안 보내는 경우가 있음(실측).
     * 실제 재생(길 안내 용도 오디오)이 시작됐다가 멈추는 것을 보고 끝을 판정. 재생이 안 잡히면 20초 뒤 종료로 간주.
     */
    private fun watchGuidancePlayback(owner: () -> Unit) {
        val startedAt = System.currentTimeMillis()
        var seenPlaying = false
        var quietSince = 0L
        val tick = object : Runnable {
            override fun run() {
                if (pendingTtsDone !== owner) return // 이미 onDone 수신 또는 새 브리핑
                val playing = runCatching {
                    audioManager.activePlaybackConfigurations.any {
                        it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    }
                }.getOrDefault(false)
                val now = System.currentTimeMillis()
                if (playing) { seenPlaying = true; quietSince = 0L } else if (quietSince == 0L) quietSince = now
                val quietLongEnough = seenPlaying && quietSince != 0L && now - quietSince > 600
                if (quietLongEnough || now - startedAt > 20_000) finishTts() else mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.postDelayed(tick, 500)
    }

    private fun finishTts() {
        runCatching { audioManager.abandonAudioFocusRequest(guidanceFocus) }
        val cb = pendingTtsDone ?: return
        pendingTtsDone = null
        mainHandler.post(cb)
    }

    override fun startVoiceConfirmation(onCommandRecognized: (VoiceDecision) -> Unit) {
        activeVoiceCallback = onCommandRecognized
        val pleos = pleosStt
        if (isPleosSttReady && pleos != null && runCatching { pleos.request() }.isSuccess) return
        mainHandler.post { startAndroidRecognizer() }
    }

    override fun stopVoiceConfirmation() {
        activeVoiceCallback = null
        runCatching { pleosStt?.stop() }
        mainHandler.post { speechRecognizer?.destroy(); speechRecognizer = null }
    }

    private fun dispatchVoice(decision: VoiceDecision) {
        val cb = activeVoiceCallback ?: return
        activeVoiceCallback = null
        mainHandler.post { cb(decision) }
    }

    private fun startAndroidRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.i(TAG, "SpeechRecognizer unavailable; awaiting Gleo intent or UI tap.")
            return
        }
        runCatching {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) { Log.w(TAG, "SpeechRecognizer error code=$error") }
                override fun onPartialResults(partialResults: Bundle?) = match(partialResults)
                override fun onResults(results: Bundle?) = match(results)

                private fun match(bundle: Bundle?) {
                    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        .firstNotNullOfOrNull { VoiceCommandParser.parse(it) }
                        ?.let(::dispatchVoice)
                }
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        }.onFailure { e -> Log.w(TAG, "SpeechRecognizer start failed: ${e.message}") }
    }

    fun shutdown() {
        restoreAmbientSync()
        vehicle?.let { v ->
            runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
            runCatching { v.getFuel().unregisterFuelPercent(fuelListener) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { v.getGear().unregisterGearSelection(gearListener) }
            runCatching { v.getOdometer().unregisterRangeRemainingListener(rangeListener) }
            runCatching { v.release() }
        }
        runCatching { carProperty?.unregisterCallback(speedCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        fusedLocation?.let { runCatching { it.unregisterFusedLocationCallback(locationListener); it.release() } }
        runCatching { naviHelper?.release() }
        pleosTts?.let { runCatching { it.removeEventListener(ttsEvents); it.release() } }
        pleosStt?.let { runCatching { it.removeListener(sttResults); it.release() } }
        mainHandler.post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            runCatching { androidTts?.stop(); androidTts?.shutdown() }
            androidTts = null
        }
    }

    private fun logVehicleError(e: Exception) {
        Log.w(TAG, "Vehicle listener failed: ${e.message}")
    }

    private fun restoreAmbientSync() = applyAmbient(AmbientAlertColor.NONE)

    private fun emit(transform: (LocalVehicleSnapshot) -> LocalVehicleSnapshot) {
        val current = _telemetryFlow.value
        if (transform(current) == current) return // 같은 값 반복 이벤트(RANGE_REMAINING 등)는 재구성 없이 무시
        _telemetryFlow.update(transform)
        _vehicleUpdates.tryEmit(transform)
    }

    private inline fun <T> sdkOrNull(name: String, block: () -> T?): T? =
        runCatching(block).onFailure { Log.i(TAG, "Pleos $name SDK unavailable: ${it.message}") }.getOrNull()

    companion object {
        private const val TAG = "PleosNativeGateway"
    }
}

internal fun VehicleGear.toGearState(): GearState = when (this) {
    VehicleGear.GEAR_PARK -> GearState.P
    VehicleGear.GEAR_REVERSE -> GearState.R
    VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_UNKNOWN -> GearState.N
    else -> GearState.D
}

internal fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}
