# 오디 (Odee) — 차량 데이터 기반 AI 그룹 주행 관제 및 심리스 보이스 링크 플랫폼

> **오디 (Odee)**는 "어디?"의 귀여운 표현으로, 그룹 주행 중 일행의 위치와 주행 맥락을 쉽고 안전하게 공유한다는 의미를 담은 현대자동차·기아 **Pleos Playground 차량용 앱 개발 공모전** 출품 및 라이브 시연을 위한 통합 레포지토리입니다.

## 주요 문서
- [AI_Group_Driving_Specification_v3.md](./docs/AI_Group_Driving_Specification_v3.md): Pleos Connect SDK 매핑, 4대 SDV 확장 기능, 멀티 맵 아키텍처를 반영한 제출용 마스터 기획서 (v3.0)
- [future_roadmap_multimap.md](./docs/future_roadmap_multimap.md): 서드파티 내비게이션(TMAP·네이버지도·카카오내비) B2B 양방향 통합 확장 로드맵
- [AI_Group_Driving_Specification.md](./AI_Group_Driving_Specification.md): 초기 기획 초안 (v2.0)

## 프로젝트 구성
- `packages/shared-types`: 서버 · 컴패니언 웹 · 시연 대시보드 공통 TypeScript/Zod 통신 프로토콜
- `apps/server`: Node.js + TypeScript(`Fastify` + `ws` + `livekit-server-sdk`) 실시간 관제 백엔드, 멀티 맵 라우팅 어댑터(`MapRoutingProvider`), 2단계 하이브리드 AI 맥락 판단 엔진, 휘발성 인메모리 세션 스토어
- `apps/pleos-ivi`: Android Automotive OS(`Kotlin` + `Jetpack Compose`) Pleos Connect 차량용 네이티브 앱 (`VehiclePlatformGateway`, `AudioFocus` Ducking, `Vehicle Light` 앰비언트 라이트 알림, 1/3 스플릿 적응형 안전 UX)
- `apps/companion-web`: 무설치 모바일 컴패니언 PWA (초대 링크 합류, 시나리오 B 대기자 우회 경로 추천, 비-Pleos 타차량 동행, 카카오내비/TMAP/네이버지도 범용 딥링크 런처)
- `apps/simulator-dashboard`: 공모전 본선 라이브 시연용 멀티차량 주행·이탈·배터리 시나리오 컨트롤 타워 및 와이드 IVI 미러

## 빠른 시작 (Quick Start)

### 1. 의존성 설치 및 검증
```bash
pnpm install
pnpm build
pnpm test
```

### 2. 실시간 관제 서버 · 시연 대시보드 · 모바일 컴패니언 실행
```bash
# (선택) 로컬 LiveKit WebRTC SFU 구동
docker compose up -d

# 터미널 1: 실시간 관제 & AI 서버 (http://localhost:4000)
pnpm dev:server

# 터미널 2: 멀티차량 시연 컨트롤 타워 (http://localhost:5174)
pnpm dev:simulator

# 터미널 3: 모바일 컴패니언 PWA (http://localhost:5173/?group=GRP-PLEOS-DEMO)
pnpm dev:companion
```

### 3. Pleos Connect AAOS 차량용 앱 빌드 및 유닛 테스트
```bash
cd apps/pleos-ivi
./gradlew testDebugUnitTest assembleDebug
```
Android Studio(Pleos Connect Emulator)에서 `apps/pleos-ivi` 프로젝트를 열어 에뮬레이터에 실행하면 호스트 머신의 관제 서버(`ws://10.0.2.2:4000/ws`)와 즉시 동기화됩니다.
