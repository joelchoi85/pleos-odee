import type { ServerWsMessage } from '@odee/shared-types';
import { describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { buildApp } from '../src/app.js';

function waitForWsMessage(
  ws: WebSocket,
  predicate: (msg: ServerWsMessage) => boolean,
  timeoutMs = 3000,
): Promise<ServerWsMessage> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMsg);
      reject(new Error('Timed out waiting for matching WebSocket message'));
    }, timeoutMs);

    function onMsg(raw: WebSocket.RawData) {
      const parsed = JSON.parse(raw.toString()) as ServerWsMessage;
      if (predicate(parsed)) {
        clearTimeout(timer);
        ws.off('message', onMsg);
        resolve(parsed);
      }
    }

    ws.on('message', onMsg);
  });
}

describe('WebSocket Real-Time Multi-Vehicle E2E Flow', () => {
  it('synchronizes Car 1, Car 2 deviation, voice/route acceptance, and ephemeral wipe over live WebSocket', async () => {
    const { app, sessionService } = await buildApp();
    await app.listen({ port: 0, host: '127.0.0.1' });
    const address = app.server.address();
    const port = typeof address === 'object' && address ? address.port : 0;

    const car1Ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    const companionWs = new WebSocket(`ws://127.0.0.1:${port}/ws`);

    await Promise.all([
      new Promise((res) => car1Ws.once('open', res)),
      new Promise((res) => companionWs.once('open', res)),
    ]);

    // 1. 1호차 & 모바일 컴패니언 세션 입장
    const car1SnapPromise = waitForWsMessage(
      car1Ws,
      (m) => m.type === 'SESSION_SNAPSHOT',
    );
    car1Ws.send(
      JSON.stringify({
        type: 'JOIN_GROUP',
        groupId: 'GRP-PLEOS-DEMO',
        memberId: 'HYUNDAI-IONIQ5-01',
        displayName: '1호차 (아이오닉 5)',
        role: 'PLEOS_HOST',
      }),
    );
    await car1SnapPromise;

    const compSnapPromise = waitForWsMessage(
      companionWs,
      (m) => m.type === 'SESSION_SNAPSHOT' && m.snapshot.members.length >= 2,
    );
    companionWs.send(
      JSON.stringify({
        type: 'JOIN_GROUP',
        groupId: 'GRP-PLEOS-DEMO',
        memberId: 'MOB-NAV-01',
        displayName: '김민수 (대기자)',
        role: 'MOBILE_NAVIGATOR',
      }),
    );
    await compSnapPromise;

    // 2. 시나리오 B: 모바일 컴패니언이 분당-내곡 8분 단축 경로 제안 -> 1호차가 GROUP_ALERT 수신
    const alertOnCar1Promise = waitForWsMessage(
      car1Ws,
      (m) => m.type === 'GROUP_ALERT',
    );
    companionWs.send(
      JSON.stringify({
        type: 'NAVIGATOR_PROPOSE_ROUTE',
        payload: {
          groupId: 'GRP-PLEOS-DEMO',
          proposerId: 'MOB-NAV-01',
          proposerName: '김민수 (대기자)',
          waypoint: {
            name: '분당-내곡 도시고속화도로 우회',
            lat: 37.448,
            lng: 127.089,
          },
        },
      }),
    );

    const alertMsg = await alertOnCar1Promise;
    expect(alertMsg.type).toBe('GROUP_ALERT');
    if (alertMsg.type !== 'GROUP_ALERT') throw new Error('Expected GROUP_ALERT');
    expect(alertMsg.alert.etaDeltaMinutes).toBe(-8);

    // 3. 1호차 운전자가 ACCEPT_AND_CALL 수락 -> 컴패니언 웹이 ALERT_RESOLVED(GREEN_VOICE_ACTIVE) 수신
    const resolvedOnCompanionPromise = waitForWsMessage(
      companionWs,
      (m) => m.type === 'ALERT_RESOLVED',
    );
    car1Ws.send(
      JSON.stringify({
        type: 'ALERT_DECISION',
        payload: {
          groupId: 'GRP-PLEOS-DEMO',
          eventId: alertMsg.alert.eventId,
          memberId: 'HYUNDAI-IONIQ5-01',
          decision: 'ACCEPT_AND_CALL',
        },
      }),
    );

    const resolvedMsg = await resolvedOnCompanionPromise;
    expect(resolvedMsg.type).toBe('ALERT_RESOLVED');
    if (resolvedMsg.type === 'ALERT_RESOLVED') {
      expect(resolvedMsg.voiceActive).toBe(true);
      expect(resolvedMsg.ambientColor).toBe('GREEN_VOICE_ACTIVE');
    }

    // 3-1. 1:1 통화 호출: 그룹 방 접두어면 상대에게 전달, 다른 방이면 거부
    const room = sessionService.getSession('GRP-PLEOS-DEMO')!.activeVoiceRoomName;
    const inviteOnCompanion = waitForWsMessage(companionWs, (m) => m.type === 'VOICE_INVITE');
    const invite = (roomName: string) =>
      car1Ws.send(
        JSON.stringify({
          type: 'VOICE_INVITE',
          groupId: 'GRP-PLEOS-DEMO',
          fromMemberId: 'HYUNDAI-IONIQ5-01',
          fromDisplayName: '1호차',
          toMemberId: 'MOB-NAV-01',
          roomName,
        }),
      );
    invite(`${room}-HYUNDAI-IONIQ5-01-MOB-NAV-01`);
    const inviteMsg = await inviteOnCompanion;
    if (inviteMsg.type !== 'VOICE_INVITE') throw new Error('Expected VOICE_INVITE');
    expect(inviteMsg.toMemberId).toBe('MOB-NAV-01');
    // 거절은 원래 발신자에게 전달
    const declineOnCar = waitForWsMessage(car1Ws, (m) => m.type === 'VOICE_DECLINE');
    companionWs.send(
      JSON.stringify({
        type: 'VOICE_DECLINE',
        groupId: 'GRP-PLEOS-DEMO',
        fromMemberId: 'MOB-NAV-01',
        fromDisplayName: '김민수',
        toMemberId: 'HYUNDAI-IONIQ5-01',
        roomName: inviteMsg.roomName,
      }),
    );
    await declineOnCar;
    // 그룹 통화 호출('*' + 그룹 방)도 전달
    const groupInvite = waitForWsMessage(companionWs, (m) => m.type === 'VOICE_INVITE' && m.roomName === room);
    car1Ws.send(
      JSON.stringify({
        type: 'VOICE_INVITE',
        groupId: 'GRP-PLEOS-DEMO',
        fromMemberId: 'HYUNDAI-IONIQ5-01',
        fromDisplayName: '1호차',
        toMemberId: '*',
        roomName: room,
      }),
    );
    await groupInvite;
    const rejected = waitForWsMessage(car1Ws, (m) => m.type === 'ERROR');
    invite('ROOM-OTHER-GROUP');
    await rejected;

    // 4. 목적지 도착으로 세션 종료 -> 양쪽 모두 SESSION_TERMINATED 수신 및 인메모리 완전 소거 확인
    const terminatedPromise = waitForWsMessage(
      companionWs,
      (m) => m.type === 'SESSION_TERMINATED',
    );
    car1Ws.send(
      JSON.stringify({
        type: 'END_GROUP_SESSION',
        groupId: 'GRP-PLEOS-DEMO',
        reason: 'ARRIVED_DESTINATION',
      }),
    );
    await terminatedPromise;
    expect(sessionService.getActiveSessionCount()).toBe(0);

    car1Ws.close();
    companionWs.close();
    await app.close();
  });
});
