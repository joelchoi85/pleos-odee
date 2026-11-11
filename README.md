# 오디 (Odee)

**여러 대가 함께 달릴 때, 일행이 어디서 왜 멀어졌는지 차가 먼저 알려 주고 말 한마디로 경유·통화까지 이어 주는 Pleos Connect 그룹 주행 앱.**

"어디?"를 친근하게 부른 이름입니다. 현대자동차·기아 Pleos Playground 차량용 앱 개발 공모전 출품작.

- 기획서: [docs/AI_Group_Driving_Specification_v3.md](./docs/AI_Group_Driving_Specification_v3.md)
- 확장 로드맵: [docs/future_roadmap_multimap.md](./docs/future_roadmap_multimap.md)

## APK
- **`apk/최중열_Odee_release.apk`** (release, v1.0.0, arm64-v8a·x86_64, 약 95MB)
- 직접 빌드: `cd apps/pleos-ivi && ./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- 제출 APK에는 Pleos 앱 자격증명이 들어 있지 않아 TTS/STT가 **ON_DEVICE** 모드로 동작합니다(오프라인, 기능 동일). 자격증명은 `local.properties`에 넣고 직접 빌드하면 HYBRID 모드
- 차량 앱은 호스트 PC의 관제 서버(`10.0.2.2:4000`)·LiveKit(`10.0.2.2:7880`)에 접속하므로, 아래 실행 방법대로 서버를 먼저 띄워야 합니다

## 개발·테스트 환경
| 항목 | 값 |
| :--- | :--- |
| 에뮬레이터 | **Pleos Connect Emulator v2.1.0** (AVD `Pleos_Connect_v2.1.0`, `pleos_car_emulator` 시스템 이미지, Android 14 / API 34, arm64-v8a, RAM 4GB) |
| 화면 규격 | **2560 × 1440 px, 가로(landscape), 160 dpi** — 오디는 전체 화면과 Pleos 화면 분할(우측 드로워, 폭 900dp 미만 레이아웃) 모두 지원 |
| 앱 | `com.hyundai.pleos.syncdrive`, minSdk 29 / targetSdk 34, Kotlin + Jetpack Compose |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `FusedLocation:1.1.3`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1` (공개 Maven `nexus-playground.pleos.ai`) |
| 빌드 | JDK 21 (Gradle 8.11은 JDK 22 이상 불가), Android SDK 34 |
| 서버·웹 | Node.js 26, pnpm 12 (`packageManager` 고정), Docker Compose(LiveKit) |
| 개발 호스트 | macOS (Apple Silicon) |

## 실행 방법

### 1. 서버·웹 (호스트 PC)
```bash
pnpm install
pnpm build && pnpm test        # 공통 프로토콜 → 서버 → 웹 빌드·테스트

cp .env.example .env           # 아래 '필요한 설정' 참고
docker compose up -d           # LiveKit (그룹 통화)

pnpm dev:server                # 관제 서버      http://localhost:4000
pnpm dev:simulator             # 시연 컨트롤 타워 http://localhost:5174
pnpm dev:companion             # 모바일 컴패니언 http://localhost:5173/?group=GRP-PLEOS-DEMO
```

### 2. 차량 앱 (Pleos Connect 에뮬레이터)
```bash
cd apps/pleos-ivi
JAVA_HOME=<JDK 21 경로> ./gradlew testDebugUnitTest assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk   # 또는 Release APK
```
에뮬레이터에서 오디를 실행하고 위치·음성 사용에 동의하면, 호스트의 관제 서버(`ws://10.0.2.2:4000/ws`)에 1호차(호스트)로 접속합니다.

### 3. 시연 흐름
1. 컨트롤 타워(`:5174`)에서 프리셋 **0** → 1~3호차 그룹 주행 시작 (차량 화면에 일행 카드·대열선 표시)
2. 컴패니언(`:5173`)을 폰 크기 브라우저로 열어 목적지 대기자로 합류
3. 프리셋 **1** 분기점 이탈 · **2** 저배터리 충전소 진입 · **3** 선제 경유 제안 · **4** 대기자 우회 경로 추천 · **5** 도착·세션 파기
4. 컨트롤 타워의 주행 안전 등급 버튼(정차 / 도심 42km/h / 고속 92km/h)으로 주행 중 알림(지도 유지·배너+음성)과 정차 중 알림(전체 화면)의 차이를 확인
5. Gleo: "오디 일행 어디쯤이야?" (통화 중에는 Gleo가 호출되지 않으므로 마이크·통화 종료는 화면 버튼)

## 필요한 설정
**모두 선택 사항입니다. 키가 없어도 시연 전 기능이 대체 경로로 동작합니다.**

| 설정 | 위치 | 없을 때 |
| :--- | :--- | :--- |
| `KAKAO_REST_API_KEY` | `.env` | 경로·주변 충전소 검색이 내장 mock 데이터로 동작 |
| `GEMINI_API_KEY` | `.env` | 브리핑 문장을 룰 기반으로 생성 (`AI_PROVIDER=RULE_ONLY`와 같음) |
| `LIVEKIT_NODE_IP` | `.env` | 에뮬레이터·폰이 LiveKit 미디어에 닿지 않아 통화 음성이 안 들릴 수 있음. Mac은 `ipconfig getifaddr en0` 값 입력 |
| `pleos.clientId` / `pleos.clientSecret` | `apps/pleos-ivi/local.properties` | Pleos TTS/STT가 ON_DEVICE 모드로 동작 (등록 시 HYBRID) |

- `.env`, `local.properties`는 커밋하지 않습니다. `.env.example`의 LiveKit 키는 로컬 Docker 전용 개발 키입니다.
- 에뮬레이터 음성 입력(Gleo·음성 응답): 에뮬레이터 확장 설정 → Microphone → **Virtual microphone uses host audio input** 켜기.
- 실제 폰으로 컴패니언을 열 때는 `localhost` 대신 호스트 PC의 LAN IP로 접속합니다(같은 Wi-Fi).

## 프로젝트 구성
- `packages/shared-types` — 차량·서버·웹 공통 통신 프로토콜 (TypeScript + Zod)
- `apps/server` — 관제 서버 (Fastify + WebSocket + LiveKit 토큰), 지도 어댑터 `MapRoutingProvider`, 이탈 맥락 판단, 인메모리 휘발성 세션
- `apps/pleos-ivi` — Pleos Connect 차량 앱 (AAOS, Kotlin + Jetpack Compose)
- `apps/companion-web` — 무설치 모바일 컴패니언 PWA (React)
- `apps/simulator-dashboard` — 시연용 멀티 차량 시나리오 컨트롤 타워 (React)
