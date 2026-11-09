# 향후 기술 과제: 멀티 내비게이션 생태계 통합(Multi-Map Agnostic) 확장 로드맵

본 문서는 **오디 (Odee)**가 기본 지원하는 `MapRoutingProvider` 추상화 계층 및 범용 내비 딥링크 런처를 넘어, 향후 상용화 단계에서 **국내외 모든 서드파티 내비게이션(TMAP, 카카오내비, 네이버지도, Google Maps 등)의 실시간 주행 세션을 양방향으로 완벽히 아우르기 위한 단계별 기술 로드맵**을 정의합니다.

---

## 1. 단계별 멀티 맵 통합 아키텍처

| 단계 | 적용 시점 | 통합 수준 | 핵심 기술 메커니즘 |
| :--- | :--- | :--- | :--- |
| **Stage 1**<br>(현재 구현 완료) | 공모전 본선 시연 | **백엔드 라우팅 추상화 + 순정 `NaviHelper` + 모바일 범용 딥링크** | • 서버 `MapRoutingProvider` 인터페이스로 지도 API 결합도 제거<br>• Pleos 차량은 `NaviHelper SDK`로 즉시 경유지 반영<br>• 타차량 스마트폰은 `kakaonavi://`, `tmap://`, `nmap://` URI 스킴으로 원하는 내비 실행 |
| **Stage 2**<br>(단기 고도화) | 전시 및 파일럿 | **다중 라우팅 엔진 동시 비교 (Multi-Engine ETA Consensus)** | • `KakaoMobilityProvider`, `TmapRoutesProvider`, `NaverDirectionsProvider` 병렬 쿼리<br>• 각 일행이 선호하는 내비 엔진 기준 ETA 편차를 보정하고 최적 합류 지점 도출 |
| **Stage 3**<br>(상용화 B2B 제휴) | Pleos App Market 정식 출시 | **서드파티 AAOS 내비 앱 간 실시간 경로 이벤트 양방향 동기화** | • Pleos Connect OS 레벨 `Navigation State Broadcast` 표준 인텐트/AIDL 제안<br>• 차량 내 TMAP Auto 등 서드파티 내비 실행 시에도 경로 이탈·경유지 삽입 양방향 연동 |

---

## 2. 현재 반영된 확장형 코드 인터페이스

`apps/server/src/providers/map-routing.interface.ts`는 벤더별 응답 포맷 차이를 내부에서 흡수하고, AI 이탈 감지 엔진에는 표준 WGS84 폴리라인(`GeoPoint[]`)과 정규화된 POI 카테고리(`EV_CHARGER`, `GAS_STATION`, `REST_AREA`)만 노출합니다. 따라서 새로운 지도 사업자 API를 추가할 때 AI 판단 로직이나 Pleos IVI 클라이언트 코드는 단 한 줄도 수정할 필요가 없습니다.

---

## 3. 향후 해결해야 할 제도적·기술적 과제

1. **서드파티 내비 앱의 활성 경로(Active Route Polyline) 외부 노출 제한:** 현재 모바일 및 차량용 서드파티 내비 앱은 보안 및 비즈니스 정책상 현재 안내 중인 세부 경로 좌표를 타 앱에 브로드캐스트하지 않습니다. 이를 해결하기 위해 **Pleos Connect OS 차원의 표준 `SharedNavigationSession` API** 확장 또는 각 모빌리티 사와의 B2B SDK 제휴가 필요합니다.
2. **멀티 벤더 교통정보 예측 편차 보정:** 동일 구간이라도 내비게이션 엔진마다 예측 도착 시간(ETA)이 3~7분가량 차이날 수 있으므로, 시나리오 B(대기자 경로 추천) 검증 시 **동일 라우팅 엔진 기준 상대 비교(A/B Delta 검증)** 원칙을 유지해 오알림을 방지합니다.
