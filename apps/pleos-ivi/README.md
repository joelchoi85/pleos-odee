# 오디 (Odee) — AAOS In-Vehicle Infotainment App (`apps/pleos-ivi`)

Android Automotive OS (AAOS) Kotlin + Jetpack Compose landscape IVI application for **Hyundai Pleos Connect**.

## Key Capabilities

1. **Driving Safety Level State Machine (`domain/DrivingSafetyMachine.kt`)**
   - `STATIONARY` (`speedKmh <= 1.0 && gear == P`): Unlocks full 2~4 vehicle telemetry dashboard, 6-digit invite code & Companion Navigator PWA link, and active route waypoints list.
   - `NORMAL_DRIVING` (`< 60 km/h`, non-braking): Distraction-optimized Navigation HUD + matrix comparison cards on `GROUP_ALERT`.
   - `HIGH_FOCUS` (`speedKmh >= 60.0 || isBraking`): Ultra-minimal glanceable alert card, Gleo AI TTS briefing + hands-free voice confirmation, and single oversized safe touch target with 8-second auto-fallback to `KEEP_ROUTE`.

2. **Pleos SDK Gateway Abstraction (`sdk/`)**
   - `VehiclePlatformGateway` interface implemented by:
     - `PleosNativeGatewayImpl`: Android `TextToSpeech` + `SpeechRecognizer` + Gleo AI intent bridge (`com.pleos.gleo.action.GROUP_DRIVING_COMMAND`) + reflection-safe runtime binding for `ai.pleos.playground.vehicle.Vehicle` (`getEvBattery`, `getFuel`, `getBrake`, `getDrivingMode`, `getLight`), `NaviHelper`, and `FusedLocation`.
     - `SimulatorBridgeGatewayImpl`: Live runtime speed (`0 / 45 / 90 km/h`), Gear (`P / D`), Brake, and SOC % controls + stack-backed ambient light restoration (`restoreAmbientState()`).

3. **In-Car Audio Ducking & WebRTC Voice Session (`audio/InCarVoiceSessionManager.kt`)**
   - Acquires `AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (`USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`) to duck media playback to 30%.
   - Attaches Android hardware `AcousticEchoCanceler` (AEC) and `NoiseSuppressor` (NS).
   - Transitions cabin ambient lighting to `GREEN_VOICE_ACTIVE` during active calls and guarantees `abandonAudioFocusRequest` + previous ambient light restoration upon hangup.

4. **Adaptive Real-Time WebSocket Sync (`network/GroupSyncWebSocketClient.kt`)**
   - Connects to `ws://10.0.2.2:4000/ws`, joins group as `PLEOS_HOST`, and adapts `TELEMETRY_UPDATE` cadence dynamically between `1000ms` and `10000ms` based on `TELEMETRY_ACK.nextIntervalMs`.

## Build & Test Commands

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew testDebugUnitTest assembleDebug
```

## Triggering Gleo AI Voice Action via ADB on AAOS Emulator

```bash
adb shell am broadcast \
  -a com.pleos.gleo.action.GROUP_DRIVING_COMMAND \
  --es command "수락하고 보이스 연결"
```
