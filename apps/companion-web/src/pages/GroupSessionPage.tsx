import type {
  GeoPoint,
  GroupAlertEvent,
  GroupSessionSnapshot,
  MemberRole,
  ServerWsMessage,
} from '@odee/shared-types';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLiveKitVoice } from '../hooks/useLiveKitVoice.js';
import {
  buildUniversalNaviUrls,
  launchExternalNavigation,
} from '../utils/universalNaviLauncher.js';

const SERVER_HTTP =
  import.meta.env.VITE_SERVER_HTTP_URL || 'http://localhost:4000';
const SERVER_WS = import.meta.env.VITE_SERVER_WS_URL || 'ws://localhost:4000/ws';

function projectPoint(pt: GeoPoint): { x: number; y: number } {
  // 판교(37.40, 127.02) ~ 강남(37.53, 127.13) 영역을 320x200 SVG 뷰포트로 정규화
  const minLat = 37.395;
  const maxLat = 37.53;
  const minLng = 127.02;
  const maxLng = 127.13;
  const x = ((pt.lng - minLng) / (maxLng - minLng)) * 280 + 20;
  const y = 185 - ((pt.lat - minLat) / (maxLat - minLat)) * 165;
  return {
    x: Math.max(12, Math.min(308, x)),
    y: Math.max(12, Math.min(188, y)),
  };
}

export function GroupSessionPage() {
  const initialGroup =
    new URLSearchParams(window.location.search).get('group') ||
    'GRP-PLEOS-DEMO';

  const [groupInput, setGroupInput] = useState(initialGroup);
  const [activeGroupId, setActiveGroupId] = useState(initialGroup);
  const [role, setRole] = useState<MemberRole>('MOBILE_NAVIGATOR');
  const [memberName, setMemberName] = useState('김민수 (대기자 · 스마트폰)');
  const [memberId] = useState(
    () => `MOB-${Math.random().toString(36).slice(2, 7).toUpperCase()}`,
  );

  const [snapshot, setSnapshot] = useState<GroupSessionSnapshot | null>(null);
  const [wsConnected, setWsConnected] = useState(false);
  const [statusBanner, setStatusBanner] = useState<string | null>(null);
  const [lastAlert, setLastAlert] = useState<GroupAlertEvent | null>(null);
  const [incomingCall, setIncomingCall] = useState<{
    from: string;
    fromMemberId: string;
    roomName: string;
    group: boolean;
  } | null>(null);
  const [terminatedMessage, setTerminatedMessage] = useState<string | null>(
    null,
  );
  const wsRef = useRef<WebSocket | null>(null);

  const sendVoiceStateToWs = useCallback(
    (inVoiceCall: boolean) => {
      if (wsRef.current?.readyState === WebSocket.OPEN && snapshot) {
        wsRef.current.send(
          JSON.stringify({
            type: 'VOICE_STATE_CHANGE',
            groupId: snapshot.groupId,
            memberId,
            inVoiceCall,
          }),
        );
      }
    },
    [memberId, snapshot],
  );

  const voice = useLiveKitVoice({
    serverHttpBase: SERVER_HTTP,
    groupId: snapshot?.groupId || activeGroupId,
    memberId,
    displayName: memberName,
    onVoiceStateChanged: sendVoiceStateToWs,
  });

  // ws.onmessage는 첫 렌더 클로저라 최신 voice를 ref로 참조
  const voiceRef = useRef(voice);
  voiceRef.current = voice;

  /** 방에 먼저 들어간 뒤 상대(또는 '*' = 그룹 전원)를 호출. 차량 앱과 같은 방 이름 규칙 */
  const callInto = useCallback(
    async (toMemberId: string) => {
      if (!snapshot) return;
      const base = snapshot.activeVoiceRoomName;
      const roomName = toMemberId === '*' ? base : `${base}-${[memberId, toMemberId].sort().join('-')}`;
      const someoneInCall = snapshot.members.some((m) => m.inVoiceCall && m.memberId !== memberId);
      await voice.disconnectVoice();
      await voice.connectVoice(undefined, roomName);
      if (toMemberId !== '*' || !someoneInCall) {
        wsRef.current?.send(
          JSON.stringify({
            type: 'VOICE_INVITE',
            groupId: snapshot.groupId,
            fromMemberId: memberId,
            fromDisplayName: memberName,
            toMemberId,
            roomName,
          }),
        );
      }
    },
    [snapshot, memberId, memberName, voice],
  );

  useEffect(() => {
    const ws = new WebSocket(SERVER_WS);
    wsRef.current = ws;

    ws.onopen = () => {
      setWsConnected(true);
      setTerminatedMessage(null);
      ws.send(
        JSON.stringify({
          type: 'JOIN_GROUP',
          groupId: activeGroupId,
          memberId,
          displayName: memberName,
          role,
        }),
      );
    };

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as ServerWsMessage;
        if (msg.type === 'SESSION_SNAPSHOT') {
          setSnapshot(msg.snapshot);
          if (msg.snapshot.activeAlert) {
            setLastAlert(msg.snapshot.activeAlert);
          }
        } else if (msg.type === 'GROUP_ALERT') {
          setLastAlert(msg.alert);
          setStatusBanner(`🔔 그룹 알림: ${msg.alert.aiBriefing.headlineKo}`);
        } else if (msg.type === 'PROPOSAL_REJECTED') {
          setStatusBanner(`🛡️ AI 안전 필터링: ${msg.reasonKo}`);
        } else if (msg.type === 'ALERT_RESOLVED') {
          setLastAlert(null);
          if (msg.decision === 'ACCEPT_AND_CALL') {
            setStatusBanner(
              '🎙️ 차량 운전자가 제안 경로를 수락하고 보이스 세션을 연결했습니다!',
            );
            void voice.connectVoice();
          } else if (msg.decision === 'ACCEPT_ROUTE_ONLY') {
            setStatusBanner('✅ 차량 내비게이션에 경유지가 반영되었습니다.');
          } else {
            setStatusBanner('ℹ️ 운전자가 기존 주행 경로를 유지했습니다.');
          }
        } else if (msg.type === 'SESSION_TERMINATED') {
          setSnapshot(null);
          void voice.disconnectVoice();
          setTerminatedMessage(
            '목적지 도착 또는 호스트 종료로 그룹 세션과 모든 실시간 위치 데이터가 서버에서 영구 파기되었습니다.',
          );
        } else if (msg.type === 'VOICE_DECLINE') {
          // 내가 건 1:1 통화를 상대가 거절 → 혼자 남은 방에서 나옴
          if (msg.toMemberId === memberId && voiceRef.current.currentRoomName() === msg.roomName) {
            void voiceRef.current.disconnectVoice();
            setStatusBanner(`📵 ${msg.fromDisplayName} 님이 통화를 거절했습니다.`);
          }
        } else if (msg.type === 'VOICE_INVITE') {
          if (msg.fromMemberId !== memberId && (msg.toMemberId === memberId || msg.toMemberId === '*')) {
            setIncomingCall({
              from: msg.fromDisplayName,
              fromMemberId: msg.fromMemberId,
              roomName: msg.roomName,
              group: msg.toMemberId === '*',
            });
          }
        } else if (msg.type === 'ERROR') {
          setStatusBanner(`⚠️ ${msg.message}`);
        }
      } catch {
        // ignore malformed packets
      }
    };

    ws.onclose = () => {
      setWsConnected(false);
    };

    return () => {
      ws.close();
    };
  }, [activeGroupId, memberId, role]);

  const sendRouteProposal = (params: {
    name: string;
    lat: number;
    lng: number;
    claimedSavingsMinutes?: number;
    noteKo: string;
  }) => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      setStatusBanner('⚠️ 서버 연결을 확인해주세요.');
      return;
    }
    wsRef.current.send(
      JSON.stringify({
        type: 'NAVIGATOR_PROPOSE_ROUTE',
        payload: {
          groupId: snapshot?.groupId || activeGroupId,
          proposerId: memberId,
          proposerName: memberName,
          waypoint: {
            name: params.name,
            lat: params.lat,
            lng: params.lng,
            poiCategory: 'ETC',
          },
          claimedSavingsMinutes: params.claimedSavingsMinutes,
          noteKo: params.noteKo,
        },
      }),
    );
    setStatusBanner(`📡 '${params.name}' 경로를 차량 AI 엔진으로 전송했습니다...`);
  };

  const broadcastCompanionCarPosition = () => {
    if (!wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(
      JSON.stringify({
        type: 'TELEMETRY_UPDATE',
        payload: {
          groupId: snapshot?.groupId || activeGroupId,
          vehicleId: memberId,
          displayName: memberName,
          role: 'MOBILE_DRIVER',
          timestampMs: Date.now(),
          location: {
            lat: 37.431,
            lng: 127.079,
            heading: 340,
            speedKmh: 72,
          },
          vehicleState: {
            powertrain: 'ICE',
            fuelPercent: 64,
            gear: 'D',
            isBraking: false,
          },
          routeState: {
            activeRouteId: 'RT-COMPANION-WEB',
            etaIso: new Date(Date.now() + 1400_000).toISOString(),
          },
        },
      }),
    );
    setStatusBanner('🚗 내 차량 위치(비-Pleos 스마트폰 동행 모드)를 그룹에 동기화했습니다.');
  };

  const naviUrls = useMemo(() => {
    const dest = snapshot?.destination ?? {
      name: '현대모터스튜디오 서울',
      lat: 37.5214,
      lng: 127.0342,
    };
    const latestWp =
      snapshot?.activeWaypoints[snapshot.activeWaypoints.length - 1];
    return buildUniversalNaviUrls(dest, latestWp);
  }, [snapshot]);

  const polylinePoints = useMemo(() => {
    const pts = snapshot?.baselinePolyline ?? [];
    return pts
      .map((p) => {
        const { x, y } = projectPoint(p);
        return `${x},${y}`;
      })
      .join(' ');
  }, [snapshot]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 pb-28">
      <div ref={voice.audioContainerRef} className="hidden" />

      {/* 상단 헤더 */}
      <header className="sticky top-0 z-20 border-b border-slate-800 bg-slate-950/90 backdrop-blur px-4 py-3 flex items-center justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-block h-2.5 w-2.5 rounded-full bg-cyan-400 animate-pulse" />
            <span className="text-xs font-semibold uppercase tracking-wider text-cyan-400">
              오디 (Odee) · 모바일 컴패니언
            </span>
          </div>
          <h1 className="text-base font-bold text-white mt-0.5">
            {snapshot?.title || '초대 링크 그룹 주행 연결'}
          </h1>
        </div>
        <span
          className={`text-xs px-2.5 py-1 rounded-full font-medium ${
            wsConnected
              ? 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/30'
              : 'bg-rose-500/15 text-rose-300 border border-rose-500/30'
          }`}
        >
          {wsConnected ? `LIVE · ${snapshot?.inviteCode ?? ''}` : '연결 대기'}
        </span>
      </header>

      <main className="max-w-md mx-auto px-4 pt-4 space-y-4">
        {/* 초대 코드 / 그룹 전환 바 */}
        <section className="rounded-2xl bg-slate-900 border border-slate-800 p-3.5 space-y-2.5">
          <div className="flex gap-2">
            <input
              value={groupInput}
              onChange={(e) => setGroupInput(e.target.value)}
              placeholder="그룹 ID 또는 6자리 초대코드"
              className="flex-1 rounded-xl bg-slate-950 border border-slate-700 px-3 py-2 text-sm text-white"
            />
            <button
              onClick={() => setActiveGroupId(groupInput.trim())}
              className="rounded-xl bg-cyan-500 hover:bg-cyan-400 text-slate-950 font-semibold px-4 py-2 text-sm transition"
            >
              합류
            </button>
          </div>
          <div className="grid grid-cols-2 gap-2 pt-1">
            <button
              onClick={() => {
                setRole('MOBILE_NAVIGATOR');
                setMemberName('김민수 (대기자 · 네비게이터)');
              }}
              className={`rounded-xl py-2 text-xs font-semibold border transition ${
                role === 'MOBILE_NAVIGATOR'
                  ? 'bg-cyan-500/20 border-cyan-400 text-cyan-200'
                  : 'bg-slate-950 border-slate-800 text-slate-400'
              }`}
            >
              🧭 목적지 대기자 (시나리오 B)
            </button>
            <button
              onClick={() => {
                setRole('MOBILE_DRIVER');
                setMemberName('3호차 (카니발 · 모바일 동행)');
              }}
              className={`rounded-xl py-2 text-xs font-semibold border transition ${
                role === 'MOBILE_DRIVER'
                  ? 'bg-indigo-500/20 border-indigo-400 text-indigo-200'
                  : 'bg-slate-950 border-slate-800 text-slate-400'
              }`}
            >
              🚗 타차량(비-Pleos) 동행
            </button>
          </div>
        </section>

        {terminatedMessage && (
          <div className="rounded-2xl border border-amber-500/40 bg-amber-500/10 p-4 text-xs text-amber-200 leading-relaxed">
            🔒 <strong>휘발성 세션 파기 완료:</strong> {terminatedMessage}
          </div>
        )}

        {incomingCall && (
          <div className="rounded-2xl border border-emerald-500/40 bg-emerald-950/60 px-3.5 py-3 space-y-2.5">
            <p className="text-sm font-bold text-emerald-100">
              📞 {incomingCall.from} 님의 {incomingCall.group ? '그룹 통화' : '통화 요청'}
            </p>
            <div className="flex gap-2">
              <button
                className="flex-1 rounded-xl bg-emerald-500 py-2.5 text-sm font-bold text-emerald-950"
                onClick={() => {
                  const { roomName } = incomingCall;
                  setIncomingCall(null);
                  // 그룹 통화 중이면 나와서 1:1 방으로
                  void voice.disconnectVoice().then(() => voice.connectVoice(undefined, roomName));
                }}
              >
                받기
              </button>
              <button
                className="flex-1 rounded-xl border border-slate-600 py-2.5 text-sm font-bold text-slate-200"
                onClick={() => {
                  setIncomingCall(null);
                  if (!incomingCall.group && snapshot) {
                    wsRef.current?.send(
                      JSON.stringify({
                        type: 'VOICE_DECLINE',
                        groupId: snapshot.groupId,
                        fromMemberId: memberId,
                        fromDisplayName: memberName,
                        toMemberId: incomingCall.fromMemberId,
                        roomName: incomingCall.roomName,
                      }),
                    );
                  }
                }}
              >
                거절
              </button>
            </div>
          </div>
        )}

        {statusBanner && (
          <div className="rounded-2xl border border-cyan-500/30 bg-cyan-950/50 px-3.5 py-2.5 text-xs text-cyan-100">
            {statusBanner}
          </div>
        )}

        {/* 실시간 그룹 주행 미니 맵 */}
        {snapshot && (
          <section className="rounded-2xl bg-slate-900 border border-slate-800 p-3.5 space-y-3">
            <div className="flex items-center justify-between text-xs">
              <span className="text-slate-400">
                {snapshot.origin.name} → {snapshot.destination.name}
              </span>
              <span className="font-semibold text-emerald-400">
                예상 {Math.round(snapshot.baselineDurationSeconds / 60)}분
              </span>
            </div>

            <div className="relative rounded-xl bg-slate-950 border border-slate-800 overflow-hidden">
              <svg viewBox="0 0 320 200" className="w-full h-48">
                <defs>
                  <pattern
                    id="grid"
                    width="20"
                    height="20"
                    patternUnits="userSpaceOnUse"
                  >
                    <path
                      d="M 20 0 L 0 0 0 20"
                      fill="none"
                      stroke="#1e293b"
                      strokeWidth="0.6"
                    />
                  </pattern>
                </defs>
                <rect width="320" height="200" fill="url(#grid)" />

                {polylinePoints && (
                  <polyline
                    fill="none"
                    stroke="#38bdf8"
                    strokeWidth="3.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    points={polylinePoints}
                  />
                )}

                {/* 경유지 마커 */}
                {snapshot.activeWaypoints.map((wp, idx) => {
                  const pos = projectPoint(wp);
                  return (
                    <g key={`${wp.name}-${idx}`}>
                      <circle
                        cx={pos.x}
                        cy={pos.y}
                        r="5"
                        fill="#10b981"
                        stroke="#ecfdf5"
                        strokeWidth="1.5"
                      />
                      <text
                        x={pos.x + 7}
                        y={pos.y + 3}
                        fill="#6ee7b7"
                        fontSize="8"
                      >
                        {wp.name}
                      </text>
                    </g>
                  );
                })}

                {/* 그룹 차량 마커 */}
                {snapshot.members
                  .filter((m) => m.lastTelemetry)
                  .map((m) => {
                    const pos = projectPoint(m.lastTelemetry!.location);
                    return (
                      <g key={m.memberId}>
                        <circle
                          cx={pos.x}
                          cy={pos.y}
                          r="7"
                          fill={m.isOffRoute ? '#f59e0b' : '#6366f1'}
                          stroke="#ffffff"
                          strokeWidth="1.8"
                        />
                        <text
                          x={pos.x + 9}
                          y={pos.y + 3}
                          fill="#f8fafc"
                          fontSize="8.5"
                          fontWeight="bold"
                        >
                          {m.displayName.slice(0, 8)}
                        </text>
                      </g>
                    );
                  })}
              </svg>
            </div>

            {/* 일행 차량 상태 카드 */}
            <div className="space-y-2">
              {snapshot.members.map((m) => {
                const soc = m.lastTelemetry?.vehicleState.socPercent;
                const fuel = m.lastTelemetry?.vehicleState.fuelPercent;
                const energy = soc !== undefined ? `⚡ ${soc}%` : fuel !== undefined ? `⛽ ${fuel}%` : '📱 대기';
                return (
                  <div
                    key={m.memberId}
                    className="flex items-center justify-between rounded-xl bg-slate-950 px-3 py-2 border border-slate-800/80 text-xs"
                  >
                    <div className="flex items-center gap-2">
                      <span
                        className={`h-2 w-2 rounded-full ${
                          m.isOffRoute ? 'bg-amber-400 animate-ping' : 'bg-emerald-400'
                        }`}
                      />
                      <span className="font-semibold text-slate-100">
                        {m.displayName}
                      </span>
                      {m.inVoiceCall && (
                        <span className="text-[10px] bg-emerald-500/20 text-emerald-300 px-1.5 py-0.5 rounded">
                          🎙️ 통화중
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2 text-slate-300">
                      {m.memberId !== memberId && (
                        <button
                          onClick={() => void callInto(m.memberId)}
                          className="rounded-lg bg-emerald-600/30 border border-emerald-500/50 px-2 py-1 text-[11px] font-bold text-emerald-200"
                        >
                          📞 통화
                        </button>
                      )}
                      <span>{energy}</span>
                      {m.lastTelemetry && (
                        <span className="text-slate-400">
                          {Math.round(m.lastTelemetry.location.speedKmh)}km/h
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        )}

        {/* 역할별 액션 패널 */}
        {role === 'MOBILE_NAVIGATOR' ? (
          <section className="rounded-2xl bg-slate-900 border border-slate-800 p-3.5 space-y-2.5">
            <h2 className="text-sm font-bold text-white">
              📬 운전 중인 일행에게 실시간 우회 경로 추천 (시나리오 B)
            </h2>
            <p className="text-xs text-slate-400">
              차량 AI가 실질 시간 단축(3분 이상)을 재검증한 뒤 안전 알림과 보이스 링크를 연결합니다.
            </p>
            <button
              onClick={() =>
                sendRouteProposal({
                  name: '분당-내곡 도시고속화도로 우회',
                  lat: 37.448,
                  lng: 127.089,
                  noteKo: '경부고속도로 양재 구간 정체 회피 (실시간 8분 단축)',
                })
              }
              className="w-full rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-bold py-3 px-4 text-sm text-left flex items-center justify-between transition"
            >
              <span>⚡ 분당-내곡 도시고속화도로 우회 추천</span>
              <span className="rounded-lg bg-slate-950/20 px-2 py-0.5 text-xs">
                8분 단축
              </span>
            </button>
            <button
              onClick={() =>
                sendRouteProposal({
                  name: '일반 이면도로 소폭 우회',
                  lat: 37.452,
                  lng: 127.051,
                  claimedSavingsMinutes: 1,
                  noteKo: '1분 미만 단축 경로 (AI 안전 차단 테스트)',
                })
              }
              className="w-full rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-medium py-2.5 px-4 text-xs text-left flex items-center justify-between transition"
            >
              <span>🧪 미세 단축 경로 전송 (AI 노이즈 필터링 검증)</span>
              <span className="text-slate-400">1분 단축</span>
            </button>
          </section>
        ) : (
          <section className="rounded-2xl bg-slate-900 border border-slate-800 p-3.5 space-y-2.5">
            <h2 className="text-sm font-bold text-white">
              🚗 비-Pleos 타차량 동행 위치 동기화
            </h2>
            <p className="text-xs text-slate-400">
              Pleos 미탑재 차량도 웹 브라우저 GPS로 그룹 대열에 참여하고 합의된 경로를 원하는 내비 앱으로 실행합니다.
            </p>
            <button
              onClick={broadcastCompanionCarPosition}
              className="w-full rounded-xl bg-indigo-500 hover:bg-indigo-400 text-white font-semibold py-2.5 text-xs transition"
            >
              📍 내 차량 현재 주행 위치 그룹에 전송하기
            </button>
          </section>
        )}

        {/* 범용 멀티 내비게이션 딥링크 실행 바 */}
        <section className="rounded-2xl bg-slate-900 border border-slate-800 p-3.5 space-y-2.5">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-bold text-white">
              🗺️ 선호 내비게이션 앱으로 동기화 경로 열기
            </h2>
            <span className="text-[11px] text-slate-400">Multi-Map Link</span>
          </div>
          <div className="grid grid-cols-3 gap-2">
            <button
              onClick={() => launchExternalNavigation(naviUrls.KAKAO_NAVI)}
              className="rounded-xl bg-amber-400/15 border border-amber-400/40 text-amber-200 py-2.5 text-xs font-bold hover:bg-amber-400/25 transition"
            >
              카카오내비
            </button>
            <button
              onClick={() => launchExternalNavigation(naviUrls.TMAP)}
              className="rounded-xl bg-fuchsia-500/15 border border-fuchsia-400/40 text-fuchsia-200 py-2.5 text-xs font-bold hover:bg-fuchsia-500/25 transition"
            >
              TMAP
            </button>
            <button
              onClick={() => launchExternalNavigation(naviUrls.NAVER_MAP)}
              className="rounded-xl bg-emerald-500/15 border border-emerald-400/40 text-emerald-200 py-2.5 text-xs font-bold hover:bg-emerald-500/25 transition"
            >
              네이버지도
            </button>
          </div>
        </section>

        {lastAlert && (
          <section className="rounded-2xl border border-amber-400/40 bg-amber-500/10 p-3.5 space-y-1.5">
            <div className="text-xs font-bold text-amber-300">
              {lastAlert.aiBriefing.headlineKo}
            </div>
            <p className="text-xs text-slate-200">
              {lastAlert.aiBriefing.detailKo}
            </p>
          </section>
        )}
      </main>

      {/* 하단 고정 WebRTC 보이스 링크 컨트롤 바 */}
      <footer className="fixed bottom-0 inset-x-0 z-30 border-t border-slate-800 bg-slate-950/95 backdrop-blur px-4 py-3">
        <div className="max-w-md mx-auto flex items-center justify-between gap-3">
          <div>
            <div className="text-xs font-bold text-white">
              {voice.isActive
                ? '🎙️ 그룹 보이스 링크 연결됨 (Opus AEC)'
                : '🔇 그룹 보이스 대기 중'}
            </div>
            <div className="text-[11px] text-slate-400">
              {voice.mode === 'LIVEKIT_CONNECTED'
                ? `LiveKit SFU 연결 ${
                    voice.remoteSpeakers.length > 0
                      ? `· 발화중: ${voice.remoteSpeakers.join(', ')}`
                      : ''
                  }`
                : voice.mode === 'SIGNALING_FALLBACK_ACTIVE'
                  ? '시그널링 보이스 활성 (차량 오디오 Ducking 연동)'
                  : '돌발 상황 수락 시 자동 연결 또는 수동 통화 가능'}
            </div>
          </div>

          <div className="flex items-center gap-2">
            {voice.isActive ? (
              <>
                <button
                  onClick={() => void voice.toggleMute()}
                  className="rounded-xl bg-slate-800 border border-slate-700 px-3 py-2 text-xs font-semibold text-slate-200"
                >
                  {voice.micMuted ? '마이크 켜기' : '음소거'}
                </button>
                <button
                  onClick={() => void voice.disconnectVoice()}
                  className="rounded-xl bg-rose-600 hover:bg-rose-500 px-3.5 py-2 text-xs font-bold text-white"
                >
                  종료
                </button>
              </>
            ) : (
              <button
                onClick={() => void callInto('*')}
                className="rounded-xl bg-emerald-500 hover:bg-emerald-400 px-4 py-2 text-xs font-bold text-slate-950"
              >
                보이스 연결
              </button>
            )}
          </div>
        </div>
      </footer>
    </div>
  );
}
