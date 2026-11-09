import type { VehicleTelemetryPayload } from '@odee/shared-types';
import { describe, expect, it } from 'vitest';
import { buildApp } from '../src/app.js';
import { DeviationAiService } from '../src/services/deviation-ai.service.js';

function createTelemetry(
  overrides: Partial<VehicleTelemetryPayload> & {
    lat: number;
    lng: number;
    socPercent?: number;
    speedKmh?: number;
    remainingRangeKm?: number;
  },
): VehicleTelemetryPayload {
  return {
    groupId: overrides.groupId ?? 'GRP-PLEOS-DEMO',
    vehicleId: overrides.vehicleId ?? 'HYUNDAI-IONIQ6-02',
    displayName: overrides.displayName ?? '2호차 (아이오닉 6)',
    role: 'PLEOS_COMPANION',
    timestampMs: Date.now(),
    location: {
      lat: overrides.lat,
      lng: overrides.lng,
      heading: 0,
      speedKmh: overrides.speedKmh ?? 85,
    },
    vehicleState: {
      powertrain: 'EV',
      socPercent: overrides.socPercent ?? 78,
      remainingRangeKm: overrides.remainingRangeKm ?? 310,
      gear: 'D',
      isBraking: false,
    },
    routeState: {
      activeRouteId: 'RT-BASE-01',
      etaIso: new Date(Date.now() + 1800_000).toISOString(),
    },
  };
}

describe('@odee/server Group Driving Control & Hybrid AI', () => {
  it('filters single-tick GPS multipath noise and triggers ACCIDENTAL_MISSED_EXIT on 2 consecutive off-route ticks (SOC 78%)', async () => {
    const { app, sessionService } = await buildApp();

    // 1회성 오차 (이탈 > 120m 지점) -> 알림 미발생
    const tick1 = await sessionService.ingestTelemetry(
      createTelemetry({ lat: 37.4055, lng: 127.115, socPercent: 78, speedKmh: 88 }),
    );
    expect(tick1?.crossTrackDistanceMeters).toBeGreaterThan(120);
    expect(tick1?.triggeredAlert).toBeUndefined();

    // 2회 연속 이탈 확인 -> ACCIDENTAL_MISSED_EXIT 알림 + LiveKit 토큰 발급
    const tick2 = await sessionService.ingestTelemetry(
      createTelemetry({ lat: 37.406, lng: 127.118, socPercent: 78, speedKmh: 88 }),
    );
    expect(tick2?.triggeredAlert).toBeDefined();
    expect(tick2?.triggeredAlert?.category).toBe('ACCIDENTAL_MISSED_EXIT');
    expect(tick2?.triggeredAlert?.ambientColor).toBe('AMBER_DEVIATION');
    expect(
      Object.keys(tick2?.triggeredAlert?.voiceSession.participantTokens ?? {})
        .length,
    ).toBeGreaterThanOrEqual(2);

    await app.close();
  });

  it('classifies INTENTIONAL_PITSTOP_CHARGE_FUEL when SOC is 16% near Pangyo E-pit EV charger and handles LLM timeout gracefully', async () => {
    // 의도적으로 200ms 이상 지연되는 LLM Mock 주입 (timeoutMs: 40ms) -> 규칙 폴백 검증
    const slowAi = new DeviationAiService({
      timeoutMs: 40,
      customLlmFetcher: async (_input, _cat, signal) =>
        new Promise((resolve, reject) => {
          const t = setTimeout(() => resolve({ headlineKo: 'LATE' }), 500);
          signal.addEventListener('abort', () => {
            clearTimeout(t);
            reject(new Error('Aborted'));
          });
        }),
    });

    const { app, sessionService } = await buildApp({ aiService: slowAi });

    // 판교 E-pit 충전소 (37.4038, 127.0985) 반경 진입 (기준 경로에서 ~550m 이탈, SOC 16%)
    await sessionService.ingestTelemetry(
      createTelemetry({
        lat: 37.4039,
        lng: 127.0987,
        socPercent: 16,
        speedKmh: 28,
      }),
    );
    const tick2 = await sessionService.ingestTelemetry(
      createTelemetry({
        lat: 37.4038,
        lng: 127.0986,
        socPercent: 16,
        speedKmh: 18,
      }),
    );

    expect(tick2?.triggeredAlert).toBeDefined();
    expect(tick2?.triggeredAlert?.category).toBe(
      'INTENTIONAL_PITSTOP_CHARGE_FUEL',
    );
    expect(tick2?.triggeredAlert?.recommendedWaypoint?.name).toContain('E-pit');
    expect(tick2?.triggeredAlert?.aiBriefing.headlineKo).toContain('충전');

    await app.close();
  });

  it('filters out insignificant (<3 min) navigator route proposals and approves 8-min shortcut proposals (Scenario B)', async () => {
    const { app } = await buildApp();

    // 1. 2분 단축 노이즈 경로 -> 차단 (운전자 시선 분산 방지)
    const noisyRes = await app.inject({
      method: 'POST',
      url: '/api/groups/GRP-PLEOS-DEMO/propose-route',
      payload: {
        proposerId: 'USER-KAKAO-01',
        proposerName: '김민수(대기자)',
        waypoint: {
          name: '일반 이면도로 우회',
          lat: 37.45,
          lng: 127.05,
        },
        claimedSavingsMinutes: 2,
      },
    });
    expect(noisyRes.statusCode).toBe(200);
    expect(noisyRes.json().accepted).toBe(false);

    // 2. 분당-내곡 도시고속화도로 우회 (8분 단축) -> 승인 및 CYAN_PROPOSAL 알림 발행
    const fastRes = await app.inject({
      method: 'POST',
      url: '/api/groups/GRP-PLEOS-DEMO/propose-route',
      payload: {
        proposerId: 'USER-KAKAO-01',
        proposerName: '김민수(대기자)',
        waypoint: {
          name: '분당-내곡 도시고속화도로 우회',
          lat: 37.448,
          lng: 127.089,
        },
      },
    });
    expect(fastRes.statusCode).toBe(200);
    const body = fastRes.json();
    expect(body.accepted).toBe(true);
    expect(body.alert.etaDeltaMinutes).toBe(-8);
    expect(body.alert.ambientColor).toBe('CYAN_PROPOSAL');

    await app.close();
  });

  it('keeps the invite code when the same group is recreated (demo reset)', async () => {
    const { app, sessionService } = await buildApp({ seedDemoSession: false });
    const first = await sessionService.createSession({ groupId: 'GRP-RESET-TEST' });
    const second = await sessionService.createSession({ groupId: 'GRP-RESET-TEST' });
    expect(second.inviteCode).toBe(first.inviteCode);
    expect(sessionService.getSession(first.inviteCode)?.groupId).toBe('GRP-RESET-TEST');
    await app.close();
  });

  it('permanently wipes group session and telemetry from memory on session end (Zero-Trace Privacy)', async () => {
    const { app, sessionService } = await buildApp({ seedDemoSession: false });

    const created = await sessionService.createSession({
      groupId: 'GRP-PRIVACY-TEST',
      hostId: 'CAR-01',
    });
    expect(sessionService.getActiveSessionCount()).toBe(1);
    expect(sessionService.getSession(created.inviteCode)).toBeDefined();

    const delRes = await app.inject({
      method: 'DELETE',
      url: `/api/groups/${created.groupId}`,
    });
    expect(delRes.statusCode).toBe(200);
    expect(sessionService.getActiveSessionCount()).toBe(0);
    expect(sessionService.getSession(created.groupId)).toBeUndefined();
    expect(sessionService.getSession(created.inviteCode)).toBeUndefined();

    await app.close();
  });

  it('mints 1:1 voice tokens only for rooms under the group room prefix', async () => {
    const { sessionService } = await buildApp();
    const snap = sessionService.listSessions()[0];
    const base = snap.activeVoiceRoomName;
    const mint = (roomName?: string) =>
      sessionService.mintMemberVoiceToken({ groupIdOrCode: snap.groupId, memberId: 'A', displayName: 'A', roomName });

    expect((await mint(`${base}-A-B`))?.roomName).toBe(`${base}-A-B`);
    expect((await mint('ROOM-OTHER-A-B'))?.roomName).toBe(base);
    expect((await mint())?.roomName).toBe(base);
  });
});
