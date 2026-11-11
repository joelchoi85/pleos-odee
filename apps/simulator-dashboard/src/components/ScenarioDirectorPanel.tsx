import {
  deriveDrivingSafetyLevel,
  type AmbientAlertColor,
  type DrivingSafetyLevel,
  type GearState,
  type GeoPoint,
  type GroupAlertEvent,
  type GroupSessionSnapshot,
  type ServerWsMessage,
  type VehicleTelemetryPayload,
} from '@odee/shared-types';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

const SERVER_HTTP =
  import.meta.env.VITE_SERVER_HTTP_URL || 'http://localhost:4000';
const SERVER_WS = import.meta.env.VITE_SERVER_WS_URL || 'ws://localhost:4000/ws';
const COMPANION_BASE =
  import.meta.env.VITE_COMPANION_URL || 'http://localhost:5173';

function projectPoint(pt: GeoPoint): { x: number; y: number } {
  const minLat = 37.395;
  const maxLat = 37.53;
  const minLng = 127.02;
  const maxLng = 127.13;
  const x = ((pt.lng - minLng) / (maxLng - minLng)) * 460 + 30;
  const y = 270 - ((pt.lat - minLat) / (maxLat - minLat)) * 240;
  return {
    x: Math.max(16, Math.min(504, x)),
    y: Math.max(16, Math.min(284, y)),
  };
}

export function ScenarioDirectorPanel() {
  const [groupId] = useState('GRP-PLEOS-DEMO');
  const [wsConnected, setWsConnected] = useState(false);
  const [snapshot, setSnapshot] = useState<GroupSessionSnapshot | null>(null);
  const [activeAlert, setActiveAlert] = useState<GroupAlertEvent | null>(null);
  const [countdown, setCountdown] = useState<number>(0);
  const [ambientColor, setAmbientColor] = useState<AmbientAlertColor>('NONE');
  const [audioDucked, setAudioDucked] = useState(false);
  const [car1Speed, setCar1Speed] = useState(82);
  const [car1Gear, setCar1Gear] = useState<GearState>('D');
  const [car1Braking, setCar1Braking] = useState(false);
  const [lastAckIntervalMs, setLastAckIntervalMs] = useState(5000);
  const [eventLogs, setEventLogs] = useState<string[]>([]);
  const wsRef = useRef<WebSocket | null>(null);

  const car1SafetyLevel: DrivingSafetyLevel = useMemo(
    () =>
      deriveDrivingSafetyLevel({
        speedKmh: car1Speed,
        gear: car1Gear,
        isBraking: car1Braking,
      }),
    [car1Braking, car1Gear, car1Speed],
  );

  const appendLog = useCallback((line: string) => {
    const ts = new Date().toLocaleTimeString('ko-KR', { hour12: false });
    setEventLogs((prev) => [`[${ts}] ${line}`, ...prev].slice(0, 25));
  }, []);


  const connectWs = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close();
    }
    const ws = new WebSocket(SERVER_WS);
    wsRef.current = ws;

    ws.onopen = () => {
      setWsConnected(true);
      appendLog('관제 백엔드 WebSocket 연결 완료');
      ws.send(
        JSON.stringify({
          type: 'JOIN_GROUP',
          groupId,
          memberId: 'HYUNDAI-IONIQ5-01',
          displayName: '1호차 (아이오닉 5 · Pleos IVI)',
          role: 'PLEOS_HOST',
        }),
      );
    };

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data) as ServerWsMessage;
        if (msg.type === 'SESSION_SNAPSHOT') {
          setSnapshot(msg.snapshot);
        } else if (msg.type === 'TELEMETRY_ACK') {
          setLastAckIntervalMs(msg.nextIntervalMs);
          appendLog(
            `ACK [${msg.vehicleId}] 이탈거리=${msg.crossTrackDistanceMeters}m, 다음주기=${msg.nextIntervalMs}ms, 안전등급=${msg.safetyLevel}`,
          );
        } else if (msg.type === 'GROUP_ALERT') {
          setActiveAlert(msg.alert);
          setCountdown(msg.alert.timeoutSeconds);
          setAmbientColor(msg.alert.ambientColor);
          appendLog(
            `🚨 AI 알림 [${msg.alert.category ?? msg.alert.type}]: ${msg.alert.aiBriefing.headlineKo}`,
          );
          // 브리핑 음성은 차량 앱이 낸다. 여기서도 읽으면 차 소리와 겹쳐 녹화에 섞임
        } else if (msg.type === 'ALERT_RESOLVED') {
          setActiveAlert(null);
          setCountdown(0);
          setAmbientColor(msg.ambientColor);
          setAudioDucked(msg.voiceActive);
          appendLog(
            `✅ 운전자 결정 [${msg.decision}] -> 보이스=${msg.voiceActive ? 'ON (Audio Ducking 30%)' : 'OFF'}, 앰비언트=${msg.ambientColor}`,
          );
        } else if (msg.type === 'SESSION_TERMINATED') {
          setSnapshot(null);
          setActiveAlert(null);
          setAmbientColor('NONE');
          setAudioDucked(false);
          appendLog(
            `🔒 휘발성 그룹 세션 영구 파기 완료 (${msg.groupId}) — 서버 메모리 잔존 데이터 0건`,
          );
        } else if (msg.type === 'ERROR') {
          appendLog(`⚠️ 서버 알림: ${msg.message}`);
        }
      } catch {
        // ignore parse error
      }
    };

    ws.onclose = () => {
      setWsConnected(false);
    };
  }, [appendLog, groupId]);

  useEffect(() => {
    connectWs();
    return () => {
      wsRef.current?.close();
    };
  }, [connectWs]);

  const sendAlertDecision = useCallback(
    (decision: 'ACCEPT_AND_CALL' | 'ACCEPT_ROUTE_ONLY' | 'KEEP_ROUTE') => {
      if (!activeAlert || wsRef.current?.readyState !== WebSocket.OPEN) return;
      wsRef.current.send(
        JSON.stringify({
          type: 'ALERT_DECISION',
          payload: {
            groupId,
            eventId: activeAlert.eventId,
            memberId: 'HYUNDAI-IONIQ5-01',
            decision,
          },
        }),
      );
    },
    [activeAlert, groupId],
  );

  // 타임아웃 안전 기본값(KEEP_ROUTE) 카운트다운 처리
  useEffect(() => {
    if (!activeAlert || countdown <= 0) return;
    const timer = setTimeout(() => {
      if (countdown <= 1) {
        // 자동 유지 결정은 차량 앱이 보낸다(브리핑 TTS가 끝난 뒤부터 응답 시간을 셈).
        // 여기서도 보내면 1호차가 답하기 전에 알림이 닫힘 → 표시만 한다.
        appendLog('⏱️ 응답 시간 경과 표시 (자동 유지 결정은 차량 앱이 전송)');
        setCountdown(0);
      } else {
        setCountdown((c) => c - 1);
      }
    }, 1000);
    return () => clearTimeout(timer);
  }, [activeAlert, appendLog, countdown, sendAlertDecision]);

  const sendTelemetry = (payload: VehicleTelemetryPayload) => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(
      JSON.stringify({
        type: 'TELEMETRY_UPDATE',
        payload,
      }),
    );
  };

  const resetAndSeedNormalConvoy = async () => {
    await fetch(`${SERVER_HTTP}/api/groups`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        groupId,
        title: '판교 → 현대모터스튜디오 서울 패밀리 투어',
        hostId: 'HYUNDAI-IONIQ5-01',
        hostName: '1호차 (아이오닉 5 · Pleos IVI)',
      }),
    });
    connectWs();
    setTimeout(() => {
      sendTelemetry({
        groupId,
        vehicleId: 'HYUNDAI-IONIQ5-01',
        displayName: '1호차 (아이오닉 5 · Pleos)',
        role: 'PLEOS_HOST',
        timestampMs: Date.now(),
        location: { lat: 37.4162, lng: 127.0978, heading: 330, speedKmh: car1Speed },
        vehicleState: {
          powertrain: 'EV',
          socPercent: 82,
          remainingRangeKm: 340,
          gear: car1Gear,
          isBraking: car1Braking,
        },
        routeState: {
          activeRouteId: 'RT-BASE-01',
          etaIso: new Date(Date.now() + 1500_000).toISOString(),
        },
      });
      sendTelemetry({
        groupId,
        vehicleId: 'HYUNDAI-IONIQ6-02',
        displayName: '2호차 (아이오닉 6)',
        role: 'PLEOS_COMPANION',
        timestampMs: Date.now(),
        location: { lat: 37.4055, lng: 127.1032, heading: 330, speedKmh: 84 },
        vehicleState: {
          powertrain: 'EV',
          socPercent: 78,
          remainingRangeKm: 315,
          gear: 'D',
          isBraking: false,
        },
        routeState: {
          activeRouteId: 'RT-BASE-01',
          etaIso: new Date(Date.now() + 1560_000).toISOString(),
        },
      });
      sendTelemetry({
        groupId,
        vehicleId: 'KIA-EV9-03',
        displayName: '3호차 (기아 EV9)',
        role: 'PLEOS_COMPANION',
        timestampMs: Date.now(),
        location: { lat: 37.403, lng: 127.107, heading: 330, speedKmh: 80 },
        vehicleState: {
          powertrain: 'EV',
          socPercent: 68,
          remainingRangeKm: 280,
          gear: 'D',
          isBraking: false,
        },
        routeState: {
          activeRouteId: 'RT-BASE-01',
          etaIso: new Date(Date.now() + 1620_000).toISOString(),
        },
      });
      setAmbientColor('NONE');
      setAudioDucked(false);
      appendLog('🚗 1·2·3호차 정상 그룹 대열 주행 동기화 시작');
    }, 250);
  };

  const triggerScenarioA1MissedExit = () => {
    appendLog('▶️ 시나리오 A-1 주입: 2호차 판교 IC 분기점 고속 이탈 (SOC 78%, 88km/h)');
    const basePayload = {
      groupId,
      vehicleId: 'HYUNDAI-IONIQ6-02',
      displayName: '2호차 (아이오닉 6)',
      role: 'PLEOS_COMPANION' as const,
      vehicleState: {
        powertrain: 'EV' as const,
        socPercent: 78,
        remainingRangeKm: 310,
        gear: 'D' as const,
        isBraking: false,
      },
      routeState: {
        activeRouteId: 'RT-BASE-01',
        etaIso: new Date(Date.now() + 1800_000).toISOString(),
      },
    };
    sendTelemetry({
      ...basePayload,
      timestampMs: Date.now(),
      location: { lat: 37.4055, lng: 127.116, heading: 90, speedKmh: 88 },
    });
    setTimeout(() => {
      sendTelemetry({
        ...basePayload,
        timestampMs: Date.now(),
        location: { lat: 37.4062, lng: 127.1195, heading: 90, speedKmh: 86 },
      });
    }, 350);
  };

  const triggerScenarioA2LowBatteryCharger = () => {
    appendLog('▶️ 시나리오 A-2 주입: 2호차 배터리 부족(16%) 판교 E-pit 충전소 진입');
    const basePayload = {
      groupId,
      vehicleId: 'HYUNDAI-IONIQ6-02',
      displayName: '2호차 (아이오닉 6)',
      role: 'PLEOS_COMPANION' as const,
      vehicleState: {
        powertrain: 'EV' as const,
        socPercent: 16,
        remainingRangeKm: 42,
        gear: 'D' as const,
        isBraking: true,
      },
      routeState: {
        activeRouteId: 'RT-BASE-01',
        etaIso: new Date(Date.now() + 1900_000).toISOString(),
      },
    };
    // A-1(분기점 이탈) 직후에도 새 이탈로 판정되도록 먼저 경로 복귀 1틱 (서버는 경로 위로 돌아와야 isOffRoute 해제)
    sendTelemetry({
      ...basePayload,
      vehicleState: { ...basePayload.vehicleState, isBraking: false },
      timestampMs: Date.now(),
      location: { lat: 37.4055, lng: 127.1032, heading: 330, speedKmh: 60 },
    });
    sendTelemetry({
      ...basePayload,
      timestampMs: Date.now(),
      location: { lat: 37.4039, lng: 127.0987, heading: 240, speedKmh: 26 },
    });
    setTimeout(() => {
      sendTelemetry({
        ...basePayload,
        timestampMs: Date.now(),
        location: { lat: 37.4038, lng: 127.0985, heading: 240, speedKmh: 15 },
      });
    }, 350);
  };

  const triggerProactivePitStop = () => {
    appendLog('▶️ 선제적 케어 주입: 3호차(EV9) 경로 상 주행가능거리 부족(SOC 13%) 사전 감지');
    sendTelemetry({
      groupId,
      vehicleId: 'KIA-EV9-03',
      displayName: '3호차 (기아 EV9)',
      role: 'PLEOS_COMPANION',
      timestampMs: Date.now(),
      location: { lat: 37.4162, lng: 127.0978, heading: 330, speedKmh: 78 },
      vehicleState: {
        powertrain: 'EV',
        socPercent: 13,
        remainingRangeKm: 9,
        gear: 'D',
        isBraking: false,
      },
      routeState: {
        activeRouteId: 'RT-BASE-01',
        etaIso: new Date(Date.now() + 1620_000).toISOString(),
      },
    });
  };

  const triggerScenarioBShortcut = async () => {
    appendLog('▶️ 시나리오 B 주입: 목적지 대기자가 8분 단축 우회 경로 원격 전송');
    await fetch(`${SERVER_HTTP}/api/groups/${groupId}/propose-route`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        proposerId: 'NAVIGATOR-MINSU',
        proposerName: '김민수 (목적지 대기자)',
        waypoint: {
          name: '분당-내곡 도시고속화도로 우회',
          lat: 37.448,
          lng: 127.089,
        },
        noteKo: '경부고속도로 양재 IC 정체 구간 회피 (8분 단축 검증 완료)',
      }),
    });
  };

  const updateCar1DrivingMode = (params: {
    speed: number;
    gear: GearState;
    braking: boolean;
  }) => {
    setCar1Speed(params.speed);
    setCar1Gear(params.gear);
    setCar1Braking(params.braking);
    sendTelemetry({
      groupId,
      vehicleId: 'HYUNDAI-IONIQ5-01',
      displayName: '1호차 (아이오닉 5 · Pleos)',
      role: 'PLEOS_HOST',
      timestampMs: Date.now(),
      location: { lat: 37.4162, lng: 127.0978, heading: 330, speedKmh: params.speed },
      vehicleState: {
        powertrain: 'EV',
        socPercent: 82,
        remainingRangeKm: 340,
        gear: params.gear,
        isBraking: params.braking,
      },
      routeState: {
        activeRouteId: 'RT-BASE-01',
        etaIso: new Date(Date.now() + 1500_000).toISOString(),
      },
    });
  };

  const terminateEphemeralSession = () => {
    if (wsRef.current?.readyState !== WebSocket.OPEN) return;
    wsRef.current.send(
      JSON.stringify({
        type: 'END_GROUP_SESSION',
        groupId,
        reason: 'ARRIVED_DESTINATION',
      }),
    );
  };

  const ambientBorderClass =
    ambientColor === 'AMBER_DEVIATION'
      ? 'border-amber-400 shadow-[0_0_32px_rgba(251,191,36,0.45)]'
      : ambientColor === 'CYAN_PROPOSAL'
        ? 'border-cyan-400 shadow-[0_0_32px_rgba(34,211,238,0.45)]'
        : ambientColor === 'GREEN_VOICE_ACTIVE'
          ? 'border-emerald-400 shadow-[0_0_32px_rgba(52,211,153,0.45)]'
          : 'border-slate-800';

  const polylinePoints = useMemo(() => {
    return (snapshot?.baselinePolyline ?? [])
      .map((p) => {
        const { x, y } = projectPoint(p);
        return `${x},${y}`;
      })
      .join(' ');
  }, [snapshot]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 p-5">
      <header className="max-w-7xl mx-auto mb-5 flex flex-wrap items-center justify-between gap-4 border-b border-slate-800 pb-4">
        <div>
          <div className="text-xs font-bold uppercase tracking-widest text-cyan-400">
            Hyundai · Kia Pleos Playground Contest Live Demo Director
          </div>
          <h1 className="text-xl font-extrabold text-white mt-0.5">
            오디 (Odee) 멀티차량 시연 컨트롤 타워 & IVI 실시간 미러
          </h1>
        </div>
        <div className="flex items-center gap-3 text-xs">
          <a
            href={`${COMPANION_BASE}/?group=${snapshot?.inviteCode ?? groupId}`}
            target="_blank"
            rel="noreferrer"
            className="rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 px-3.5 py-2 font-semibold text-cyan-300 transition"
          >
            📱 모바일 컴패니언 PWA 열기 (초대코드: {snapshot?.inviteCode ?? 'DEMO'})
          </a>
          <span
            className={`px-3 py-2 rounded-xl font-bold ${
              wsConnected
                ? 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/30'
                : 'bg-rose-500/15 text-rose-300 border border-rose-500/30'
            }`}
          >
            {wsConnected ? '● 관제 서버 연결됨' : '○ 서버 미연결'}
          </span>
        </div>
      </header>

      <div className="max-w-7xl mx-auto grid grid-cols-1 lg:grid-cols-12 gap-5">
        {/* 좌측: 원클릭 심사 시연 프리셋 패널 */}
        <aside className="lg:col-span-4 space-y-4">
          <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-2.5">
            <h2 className="text-sm font-bold text-white">
              🎬 심사 시연 원클릭 시나리오 프리셋
            </h2>
            <button
              onClick={() => void resetAndSeedNormalConvoy()}
              className="w-full rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold py-2.5 px-3.5 text-xs text-left transition"
            >
              0. 1~3호차 정상 그룹 주행 시작 / 세션 리셋
            </button>
            <button
              onClick={triggerScenarioA1MissedExit}
              className="w-full rounded-xl bg-amber-500/20 hover:bg-amber-500/30 border border-amber-500/40 text-amber-200 font-semibold py-2.5 px-3.5 text-xs text-left transition"
            >
              1. [시나리오 A-1] 2호차 판교 IC 오진입 실수 이탈 (SOC 78%)
            </button>
            <button
              onClick={triggerScenarioA2LowBatteryCharger}
              className="w-full rounded-xl bg-amber-500/20 hover:bg-amber-500/30 border border-amber-500/40 text-amber-200 font-semibold py-2.5 px-3.5 text-xs text-left transition"
            >
              2. [시나리오 A-2] 2호차 저배터리(16%) E-pit 충전소 진입
            </button>
            <button
              onClick={triggerProactivePitStop}
              className="w-full rounded-xl bg-orange-500/20 hover:bg-orange-500/30 border border-orange-500/40 text-orange-200 font-semibold py-2.5 px-3.5 text-xs text-left transition"
            >
              3. [선제적 케어] 3호차 잔여 주행거리 부족 사전 경유 제안
            </button>
            <button
              onClick={() => void triggerScenarioBShortcut()}
              className="w-full rounded-xl bg-cyan-500/20 hover:bg-cyan-500/30 border border-cyan-500/40 text-cyan-200 font-semibold py-2.5 px-3.5 text-xs text-left transition"
            >
              4. [시나리오 B] 목적지 대기자 8분 단축 우회 경로 추천
            </button>
            <button
              onClick={terminateEphemeralSession}
              className="w-full rounded-xl bg-rose-500/15 hover:bg-rose-500/25 border border-rose-500/30 text-rose-300 font-semibold py-2.5 px-3.5 text-xs text-left transition"
            >
              5. [프라이버시 검증] 목적지 도착 · 휘발성 세션 즉시 파기
            </button>
          </div>

          {/* 1호차 Pleos Vehicle SDK 센서 주입기 */}
          <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-3">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-bold text-white">
                🎛️ 1호차 주행 안전 등급(Adaptive Safety UX) 제어
              </h2>
              <span className="text-[11px] font-mono px-2 py-0.5 rounded bg-slate-800 text-cyan-300">
                {car1SafetyLevel}
              </span>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <button
                onClick={() =>
                  updateCar1DrivingMode({ speed: 0, gear: 'P', braking: false })
                }
                className={`rounded-xl py-2 px-2 text-xs font-bold border transition ${
                  car1SafetyLevel === 'STATIONARY'
                    ? 'bg-emerald-500/25 border-emerald-400 text-emerald-200'
                    : 'bg-slate-950 border-slate-800 text-slate-400'
                }`}
              >
                정차 (P단)<br />0 km/h
              </button>
              <button
                onClick={() =>
                  updateCar1DrivingMode({ speed: 42, gear: 'D', braking: false })
                }
                className={`rounded-xl py-2 px-2 text-xs font-bold border transition ${
                  car1SafetyLevel === 'NORMAL_DRIVING'
                    ? 'bg-sky-500/25 border-sky-400 text-sky-200'
                    : 'bg-slate-950 border-slate-800 text-slate-400'
                }`}
              >
                도심 (D단)<br />42 km/h
              </button>
              <button
                onClick={() =>
                  updateCar1DrivingMode({ speed: 92, gear: 'D', braking: true })
                }
                className={`rounded-xl py-2 px-2 text-xs font-bold border transition ${
                  car1SafetyLevel === 'HIGH_FOCUS'
                    ? 'bg-amber-500/25 border-amber-400 text-amber-200'
                    : 'bg-slate-950 border-slate-800 text-slate-400'
                }`}
              >
                고속/제동<br />92 km/h
              </button>
            </div>
            <div className="text-[11px] text-slate-400 flex justify-between pt-1">
              <span>가변 전송 주기: {lastAckIntervalMs}ms</span>
              <span>
                오디오 상태: {audioDucked ? '🔉 30% DUCKED' : '🔊 100% NORMAL'}
              </span>
            </div>
          </div>

          {/* 실시간 관제 이벤트 로그 */}
          <div className="rounded-2xl bg-slate-900 border border-slate-800 p-4 space-y-2">
            <h2 className="text-sm font-bold text-white">📜 실시간 관제 & AI 판단 로그</h2>
            <div className="h-52 overflow-y-auto rounded-xl bg-slate-950 p-2.5 font-mono text-[11px] text-slate-300 space-y-1.5 border border-slate-800">
              {eventLogs.length === 0 ? (
                <div className="text-slate-500">이벤트 대기 중...</div>
              ) : (
                eventLogs.map((log, idx) => <div key={idx}>{log}</div>)
              )}
            </div>
          </div>
        </aside>

        {/* 우측: Pleos Connect 와이드 IVI 디스플레이 시뮬레이션 뷰 (2/3 주행맵 + 1/3 스플릿 세이프티 팝업) */}
        <section className="lg:col-span-8 space-y-4">
          <div
            className={`rounded-3xl bg-slate-900 border-2 p-4 transition-all duration-500 ${ambientBorderClass}`}
          >
            {/* 상단 Pleos IVI 상태 바 & 앰비언트 라이트 인디케이터 */}
            <div className="flex items-center justify-between border-b border-slate-800 pb-3 mb-4 text-xs">
              <div className="flex items-center gap-3">
                <span className="font-bold text-white">
                  PLEOS CONNECT IVI · 1호차 (아이오닉 5)
                </span>
                <span className="px-2 py-0.5 rounded bg-slate-800 text-slate-300">
                  기어 {car1Gear} · {car1Speed} km/h
                </span>
                {audioDucked && (
                  <span className="px-2.5 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 font-bold border border-emerald-500/40">
                    🎙️ 보이스톡 활성 · 미디어 볼륨 30% Ducking
                  </span>
                )}
              </div>
              <div className="flex items-center gap-2">
                <span className="text-slate-400">Vehicle Light SDK:</span>
                <span
                  className={`px-2.5 py-0.5 rounded-full font-bold ${
                    ambientColor === 'AMBER_DEVIATION'
                      ? 'bg-amber-400/20 text-amber-300'
                      : ambientColor === 'CYAN_PROPOSAL'
                        ? 'bg-cyan-400/20 text-cyan-300'
                        : ambientColor === 'GREEN_VOICE_ACTIVE'
                          ? 'bg-emerald-400/20 text-emerald-300'
                          : 'bg-slate-800 text-slate-400'
                  }`}
                >
                  {ambientColor}
                </span>
              </div>
            </div>

            {/* 와이드 인포테인먼트 화면: 좌측 2/3 내비게이션 & 우측 1/3 스플릿 안전 팝업 */}
            <div className="grid grid-cols-12 gap-4 min-h-[360px]">
              <div
                className={`${
                  activeAlert ? 'col-span-8' : 'col-span-12'
                } rounded-2xl bg-slate-950 border border-slate-800 p-4 flex flex-col justify-between transition-all`}
              >
                <div className="flex items-center justify-between mb-2">
                  <div>
                    <div className="text-xs text-slate-400">NaviHelper 순정 연동 경로</div>
                    <div className="text-sm font-bold text-white">
                      {snapshot
                        ? `${snapshot.origin.name} → ${snapshot.destination.name}`
                        : '세션 종료됨 (데이터 파기 완료)'}
                    </div>
                  </div>
                  {snapshot && (
                    <div className="text-right">
                      <div className="text-xs text-emerald-400 font-bold">
                        잔여 {Math.round(snapshot.baselineDurationSeconds / 60)}분
                      </div>
                      <div className="text-[11px] text-slate-400">
                        경유지 {snapshot.activeWaypoints.length}개 동기화됨
                      </div>
                    </div>
                  )}
                </div>

                {/* 주행 경로 & 다차량 실시간 SVG 맵 */}
                <svg
                  viewBox="0 0 520 300"
                  className="w-full h-64 rounded-xl bg-slate-900/60 border border-slate-800/80"
                >
                  {polylinePoints && (
                    <polyline
                      fill="none"
                      stroke="#38bdf8"
                      strokeWidth="5"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      points={polylinePoints}
                    />
                  )}

                  {/* 판교 E-pit 충전소 고정 참조 마커 */}
                  {(() => {
                    const epit = projectPoint({ lat: 37.4038, lng: 127.0985 });
                    return (
                      <g>
                        <rect
                          x={epit.x - 5}
                          y={epit.y - 5}
                          width="10"
                          height="10"
                          rx="2"
                          fill="#22c55e"
                        />
                        <text
                          x={epit.x - 36}
                          y={epit.y + 16}
                          fill="#86efac"
                          fontSize="9"
                        >
                          ⚡판교 E-pit
                        </text>
                      </g>
                    );
                  })()}

                  {snapshot?.activeWaypoints.map((wp, idx) => {
                    const pos = projectPoint(wp);
                    return (
                      <g key={`${wp.name}-${idx}`}>
                        <circle
                          cx={pos.x}
                          cy={pos.y}
                          r="7"
                          fill="#10b981"
                          stroke="#ffffff"
                          strokeWidth="2"
                        />
                        <text
                          x={pos.x + 10}
                          y={pos.y + 4}
                          fill="#6ee7b7"
                          fontSize="10"
                          fontWeight="bold"
                        >
                          경유: {wp.name}
                        </text>
                      </g>
                    );
                  })}

                  {snapshot?.members
                    .filter((m) => m.lastTelemetry)
                    .map((m) => {
                      const pos = projectPoint(m.lastTelemetry!.location);
                      const soc = m.lastTelemetry!.vehicleState.socPercent;
                      return (
                        <g key={m.memberId}>
                          <circle
                            cx={pos.x}
                            cy={pos.y}
                            r="9"
                            fill={m.isOffRoute ? '#f59e0b' : '#6366f1'}
                            stroke="#ffffff"
                            strokeWidth="2"
                          />
                          <text
                            x={pos.x + 12}
                            y={pos.y + 4}
                            fill="#ffffff"
                            fontSize="10.5"
                            fontWeight="bold"
                          >
                            {m.displayName.slice(0, 10)}{' '}
                            {soc !== undefined ? `(${soc}%)` : ''}
                          </text>
                        </g>
                      );
                    })}
                </svg>

                {/* 정차 시에만 열리는 상세 그룹 매트릭스 vs 주행 중 안전 잠금 배너 */}
                {car1SafetyLevel === 'STATIONARY' ? (
                  <div className="mt-3 grid grid-cols-3 gap-2">
                    {snapshot?.members.map((m) => (
                      <div
                        key={m.memberId}
                        className="rounded-xl bg-slate-900 border border-slate-800 p-2.5 text-xs"
                      >
                        <div className="font-bold text-white truncate">
                          {m.displayName}
                        </div>
                        <div className="text-slate-400 mt-0.5">
                          SOC {m.lastTelemetry?.vehicleState.socPercent ?? '--'}% · 이탈{' '}
                          {m.crossTrackDistanceMeters}m
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="mt-3 rounded-xl bg-slate-900/90 border border-slate-800 px-3 py-2 text-xs text-slate-400 flex items-center justify-between">
                    <span>
                      🛡️ 주행 안전 잠금 활성 ({car1SafetyLevel}) — 상세 차량 목록은 정차(P단) 시 열람 가능합니다.
                    </span>
                    <span className="text-cyan-400 font-semibold">
                      Gleo 음성 명령 대기 중
                    </span>
                  </div>
                )}
              </div>

              {/* 우측 1/3 스플릿 세이프티 팝업 */}
              {activeAlert && (
                <div className="col-span-4 rounded-2xl bg-slate-950 border-2 border-amber-400/70 p-4 flex flex-col justify-between">
                  <div className="space-y-3">
                    <div className="flex items-center justify-between">
                      <span
                        className={`text-xs font-extrabold px-2.5 py-1 rounded-lg ${
                          activeAlert.etaDeltaMinutes < 0
                            ? 'bg-emerald-500 text-slate-950'
                            : 'bg-amber-400 text-slate-950'
                        }`}
                      >
                        {activeAlert.etaDeltaMinutes < 0
                          ? `⚡ ${Math.abs(activeAlert.etaDeltaMinutes)}분 단축`
                          : `+${activeAlert.etaDeltaMinutes}분 소요`}
                      </span>
                      <span className="text-xs font-mono text-slate-300">
                        자동 유지 {countdown}s
                      </span>
                    </div>

                    {/* 타임아웃 게이지 */}
                    <div className="w-full h-1.5 rounded-full bg-slate-800 overflow-hidden">
                      <div
                        className="h-full bg-amber-400 transition-all duration-1000"
                        style={{
                          width: `${(countdown / activeAlert.timeoutSeconds) * 100}%`,
                        }}
                      />
                    </div>

                    <h3 className="text-base font-extrabold text-white leading-snug">
                      {activeAlert.aiBriefing.headlineKo}
                    </h3>

                    {car1SafetyLevel !== 'HIGH_FOCUS' ? (
                      <p className="text-xs text-slate-300 leading-relaxed">
                        {activeAlert.aiBriefing.detailKo}
                      </p>
                    ) : (
                      <div className="rounded-xl bg-amber-500/10 border border-amber-500/30 p-2.5 text-xs text-amber-200">
                        🎙️ 고속/제동 안전 모드: TTS 음성 안내 송출 중 · &ldquo;연결해줘&rdquo; 음성 수락 가능
                      </div>
                    )}
                  </div>

                  <div className="space-y-2 pt-3">
                    <button
                      onClick={() => sendAlertDecision('ACCEPT_AND_CALL')}
                      className="w-full rounded-xl bg-emerald-500 hover:bg-emerald-400 text-slate-950 font-extrabold py-3.5 px-3 text-sm transition"
                    >
                      🎙️ 경로 동기화 + 보이스 연결
                    </button>
                    {car1SafetyLevel !== 'HIGH_FOCUS' && (
                      <button
                        onClick={() => sendAlertDecision('KEEP_ROUTE')}
                        className="w-full rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 font-bold py-2.5 px-3 text-xs transition"
                      >
                        기존 경로 유지
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
