import { randomUUID } from 'node:crypto';
import {
  deriveDrivingSafetyLevel,
  type AlertDecisionPayload,
  type AmbientAlertColor,
  type GeoPoint,
  type GroupAlertEvent,
  type GroupMemberSnapshot,
  type GroupSessionSnapshot,
  type MemberRole,
  type NavigatorRouteProposalPayload,
  type RecommendedWaypoint,
  type VehicleTelemetryPayload,
} from '@odee/shared-types';
import {
  DEMO_CORRIDOR_POIS,
  KakaoMobilityProvider,
} from '../providers/kakao-mobility.provider.js';
import type { MapRoutingProvider } from '../providers/map-routing.interface.js';
import {
  computeAdaptiveIntervalMs,
  crossTrackDistanceToPolylineMeters,
  haversineDistanceMeters,
} from '../utils/geo.js';
import { DeviationAiService } from './deviation-ai.service.js';
import { LiveKitTokenService } from './livekit-token.service.js';

interface InternalMemberState extends GroupMemberSnapshot {
  consecutiveOffRouteTicks: number;
  lastAlertAtMs: number;
  proactiveEnergyWarned: boolean;
}

interface InternalGroupSession {
  groupId: string;
  inviteCode: string;
  title: string;
  origin: GeoPoint & { name: string };
  destination: GeoPoint & { name: string };
  activeWaypoints: RecommendedWaypoint[];
  baselinePolyline: GeoPoint[];
  baselineDurationSeconds: number;
  baselineDistanceMeters: number;
  activeVoiceRoomName: string;
  voiceCallActive: boolean;
  members: Map<string, InternalMemberState>;
  activeAlert?: GroupAlertEvent;
  createdAtMs: number;
}

export interface CreateGroupOptions {
  groupId?: string;
  title?: string;
  origin?: GeoPoint & { name: string };
  destination?: GeoPoint & { name: string };
  hostId?: string;
  hostName?: string;
}

export class EphemeralSessionService {
  private readonly sessions = new Map<string, InternalGroupSession>();
  private readonly inviteIndex = new Map<string, string>();
  private readonly mapProvider: MapRoutingProvider;
  private readonly aiService: DeviationAiService;
  private readonly livekitService: LiveKitTokenService;

  constructor(deps?: {
    mapProvider?: MapRoutingProvider;
    aiService?: DeviationAiService;
    livekitService?: LiveKitTokenService;
  }) {
    this.mapProvider = deps?.mapProvider ?? new KakaoMobilityProvider();
    this.aiService = deps?.aiService ?? new DeviationAiService();
    this.livekitService = deps?.livekitService ?? new LiveKitTokenService();
  }

  async createSession(
    opts: CreateGroupOptions = {},
  ): Promise<GroupSessionSnapshot> {
    const origin = opts.origin ?? {
      name: '판교 테크노밸리',
      lat: 37.402,
      lng: 127.1086,
    };
    const destination = opts.destination ?? {
      name: '현대모터스튜디오 서울 (강남)',
      lat: 37.5214,
      lng: 127.0342,
    };

    const route = await this.mapProvider.calculateRoute(origin, destination);
    const groupId =
      opts.groupId ??
      `GRP-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-${Math.random()
        .toString(36)
        .slice(2, 5)
        .toUpperCase()}`;
    // 같은 그룹을 다시 만들면(데모 리셋) 초대코드 유지 → 이미 열린 컴패니언 링크·차량 화면 코드가 그대로
    const inviteCode = this.sessions.get(groupId)?.inviteCode ?? this.generateUniqueInviteCode();

    const members = new Map<string, InternalMemberState>();
    if (opts.hostId) {
      members.set(opts.hostId, {
        memberId: opts.hostId,
        displayName: opts.hostName ?? '1호차 (아이오닉 5)',
        role: 'PLEOS_HOST',
        isOffRoute: false,
        crossTrackDistanceMeters: 0,
        inVoiceCall: false,
        recommendedIntervalMs: 5000,
        consecutiveOffRouteTicks: 0,
        lastAlertAtMs: 0,
        proactiveEnergyWarned: false,
      });
    }

    const session: InternalGroupSession = {
      groupId,
      inviteCode,
      title: opts.title ?? '강남 주말 패밀리 그룹 주행',
      origin,
      destination,
      activeWaypoints: [],
      baselinePolyline: route.polyline,
      baselineDurationSeconds: route.durationSeconds,
      baselineDistanceMeters: route.distanceMeters,
      activeVoiceRoomName: `ROOM-${inviteCode}`,
      voiceCallActive: false,
      members,
      createdAtMs: Date.now(),
    };

    this.sessions.set(groupId, session);
    this.inviteIndex.set(inviteCode, groupId);
    return this.toSnapshot(session);
  }

  listSessions(): GroupSessionSnapshot[] {
    return Array.from(this.sessions.values()).map((s) => this.toSnapshot(s));
  }

  getSession(groupIdOrCode: string): GroupSessionSnapshot | undefined {
    const session = this.resolveInternalSession(groupIdOrCode);
    return session ? this.toSnapshot(session) : undefined;
  }

  getActiveSessionCount(): number {
    return this.sessions.size;
  }

  joinSession(params: {
    groupIdOrCode: string;
    memberId: string;
    displayName: string;
    role: MemberRole;
  }): GroupSessionSnapshot | undefined {
    const session = this.resolveInternalSession(params.groupIdOrCode);
    if (!session) return undefined;

    const existing = session.members.get(params.memberId);
    if (existing) {
      existing.displayName = params.displayName;
      existing.role = params.role;
    } else {
      session.members.set(params.memberId, {
        memberId: params.memberId,
        displayName: params.displayName,
        role: params.role,
        isOffRoute: false,
        crossTrackDistanceMeters: 0,
        inVoiceCall: false,
        recommendedIntervalMs: 5000,
        consecutiveOffRouteTicks: 0,
        lastAlertAtMs: 0,
        proactiveEnergyWarned: false,
      });
    }

    return this.toSnapshot(session);
  }

  /**
   * 차량 텔레메트리 수신 -> 경로 이탈 판정 & 선제적 배터리/연료 부족 경유지 탐지
   */
  async ingestTelemetry(payload: VehicleTelemetryPayload): Promise<{
    snapshot: GroupSessionSnapshot;
    nextIntervalMs: number;
    crossTrackDistanceMeters: number;
    safetyLevel: ReturnType<typeof deriveDrivingSafetyLevel>;
    triggeredAlert?: GroupAlertEvent;
  } | null> {
    const session = this.resolveInternalSession(payload.groupId);
    if (!session) return null;

    let member = session.members.get(payload.vehicleId);
    if (!member) {
      member = {
        memberId: payload.vehicleId,
        displayName: payload.displayName,
        role: payload.role,
        isOffRoute: false,
        crossTrackDistanceMeters: 0,
        inVoiceCall: false,
        recommendedIntervalMs: 5000,
        consecutiveOffRouteTicks: 0,
        lastAlertAtMs: 0,
        proactiveEnergyWarned: false,
      };
      session.members.set(payload.vehicleId, member);
    }

    member.displayName = payload.displayName;
    member.lastTelemetry = payload;

    const crossTrack = crossTrackDistanceToPolylineMeters(
      payload.location,
      session.baselinePolyline,
    );
    member.crossTrackDistanceMeters = crossTrack;

    const nextIntervalMs = computeAdaptiveIntervalMs({
      speedKmh: payload.location.speedKmh,
      crossTrackMeters: crossTrack,
    });
    member.recommendedIntervalMs = nextIntervalMs;

    const safetyLevel = deriveDrivingSafetyLevel({
      speedKmh: payload.location.speedKmh,
      gear: payload.vehicleState.gear,
      isBraking: payload.vehicleState.isBraking,
    });

    if (crossTrack > DeviationAiService.OFF_ROUTE_THRESHOLD_METERS) {
      member.consecutiveOffRouteTicks += 1;
    } else {
      member.consecutiveOffRouteTicks = 0;
      member.isOffRoute = false;
    }

    let triggeredAlert: GroupAlertEvent | undefined;
    const now = Date.now();
    const cooldownElapsed = now - member.lastAlertAtMs > 20_000;

    // 1. 연속 2회 이상 이탈 확인 시 (1회성 GPS 튐 필터링)
    if (
      member.consecutiveOffRouteTicks >=
        DeviationAiService.REQUIRED_CONSECUTIVE_TICKS &&
      !member.isOffRoute &&
      cooldownElapsed
    ) {
      member.isOffRoute = true;
      member.lastAlertAtMs = now;

      const nearbyPois = await this.mapProvider.searchNearbyPois(
        payload.location,
        800,
      );
      const rejoinRoute = await this.mapProvider.calculateRoute(
        payload.location,
        session.destination,
      );
      const etaDeltaMinutes = Math.max(
        3,
        Math.round(
          (rejoinRoute.durationSeconds -
            session.baselineDurationSeconds * 0.75) /
            60,
        ),
      );

      const aiAnalysis = await this.aiService.analyzeDeviation({
        telemetry: payload,
        crossTrackDistanceMeters: crossTrack,
        nearbyPois,
        etaDeltaMinutes,
      });

      const participantTokens = await this.mintSessionTokens(session);

      triggeredAlert = {
        eventId: `EVT-${randomUUID().slice(0, 8)}`,
        groupId: session.groupId,
        createdAtMs: now,
        type: 'VEHICLE_DEVIATION',
        sourceMemberId: member.memberId,
        sourceDisplayName: member.displayName,
        category: aiAnalysis.category,
        etaDeltaMinutes,
        recommendedWaypoint: aiAnalysis.matchedPoi
          ? {
              name: aiAnalysis.matchedPoi.name,
              lat: aiAnalysis.matchedPoi.location.lat,
              lng: aiAnalysis.matchedPoi.location.lng,
              poiCategory: aiAnalysis.matchedPoi.category,
            }
          : {
              name: `${member.displayName} 합류 지점`,
              lat: payload.location.lat,
              lng: payload.location.lng,
            },
        ambientColor: 'AMBER_DEVIATION',
        aiBriefing: {
          headlineKo: aiAnalysis.headlineKo,
          detailKo: aiAnalysis.detailKo,
          ttsScriptKo: aiAnalysis.ttsScriptKo,
        },
        voiceSession: {
          roomName: session.activeVoiceRoomName,
          livekitUrl: this.livekitService.getLiveKitUrl(),
          participantTokens,
          autoConnectOnAccept: true,
        },
        timeoutSeconds: 8,
      };
      session.activeAlert = triggeredAlert;
    }

    // 2. 선제적 에너지 케어 (경로 이탈 전 주행가능거리 부족 사전 감지)
    const remainingDistanceKm =
      haversineDistanceMeters(payload.location, session.destination) / 1000;
    const dteInsufficient =
      typeof payload.vehicleState.remainingRangeKm === 'number' &&
      payload.vehicleState.remainingRangeKm < remainingDistanceKm * 1.15;
    const criticalSocOnRoute =
      !member.isOffRoute &&
      typeof payload.vehicleState.socPercent === 'number' &&
      payload.vehicleState.socPercent <= 14;

    if (
      !triggeredAlert &&
      !member.proactiveEnergyWarned &&
      (dteInsufficient || criticalSocOnRoute)
    ) {
      member.proactiveEnergyWarned = true;
      const corridorCharger = DEMO_CORRIDOR_POIS[1]; // 서울만남의광장 급속충전/주유 휴게소
      const participantTokens = await this.mintSessionTokens(session);
      const energyText =
        payload.vehicleState.socPercent !== undefined
          ? `배터리 ${payload.vehicleState.socPercent}%`
          : `주행가능거리 ${payload.vehicleState.remainingRangeKm}km`;

      triggeredAlert = {
        eventId: `EVT-${randomUUID().slice(0, 8)}`,
        groupId: session.groupId,
        createdAtMs: now,
        type: 'PROACTIVE_PITSTOP',
        sourceMemberId: member.memberId,
        sourceDisplayName: member.displayName,
        category: 'PROACTIVE_LOW_ENERGY_WARNING',
        etaDeltaMinutes: 6,
        recommendedWaypoint: {
          name: corridorCharger.name,
          lat: corridorCharger.location.lat,
          lng: corridorCharger.location.lng,
          poiCategory: corridorCharger.category,
        },
        ambientColor: 'AMBER_DEVIATION',
        aiBriefing: {
          headlineKo: `${member.displayName} 선제 충전 경유 제안`,
          detailKo: `${energyText} 감지 — 공통 경로 상 '${corridorCharger.name}'을 그룹 전체 경유지로 추가(+6분)할까요?`,
          ttsScriptKo: `${member.displayName}의 잔여 에너지가 ${energyText}로 부족합니다. 전방 ${corridorCharger.name}을 공동 경유지로 추가하고 보이스를 연결할까요?`,
        },
        voiceSession: {
          roomName: session.activeVoiceRoomName,
          livekitUrl: this.livekitService.getLiveKitUrl(),
          participantTokens,
          autoConnectOnAccept: true,
        },
        timeoutSeconds: 8,
      };
      session.activeAlert = triggeredAlert;
    }

    return {
      snapshot: this.toSnapshot(session),
      nextIntervalMs,
      crossTrackDistanceMeters: crossTrack,
      safetyLevel,
      triggeredAlert,
    };
  }

  /**
   * 시나리오 B: 목적지 대기자(네비게이터)의 원격 우회 경로/경유지 추천 및 AI 유의성 필터링
   */
  async evaluateNavigatorProposal(
    proposal: NavigatorRouteProposalPayload,
  ): Promise<
    | { accepted: true; alert: GroupAlertEvent; snapshot: GroupSessionSnapshot }
    | { accepted: false; reasonKo: string; etaDeltaMinutes: number }
    | null
  > {
    const session = this.resolveInternalSession(proposal.groupId);
    if (!session) return null;

    const candidateRoute = await this.mapProvider.calculateRoute(
      session.origin,
      session.destination,
      [
        ...session.activeWaypoints.map((w) => ({ lat: w.lat, lng: w.lng })),
        { lat: proposal.waypoint.lat, lng: proposal.waypoint.lng },
      ],
    );

    const calculatedDeltaMin = Math.round(
      (candidateRoute.durationSeconds - session.baselineDurationSeconds) / 60,
    );
    const effectiveSavingsMinutes =
      proposal.claimedSavingsMinutes !== undefined
        ? proposal.claimedSavingsMinutes
        : -calculatedDeltaMin;

    // 3분 미만 단축 경로는 운전 중 주의 분산을 막기 위해 AI 엔진이 차단
    if (
      effectiveSavingsMinutes <
      DeviationAiService.MIN_SIGNIFICANT_SAVINGS_MINUTES
    ) {
      return {
        accepted: false,
        etaDeltaMinutes: -effectiveSavingsMinutes,
        reasonKo: `예상 단축 시간(${effectiveSavingsMinutes}분)이 안전 알림 기준(${DeviationAiService.MIN_SIGNIFICANT_SAVINGS_MINUTES}분 이상)에 미달하여 운전 중 알림이 보류되었습니다.`,
      };
    }

    // 대기자 멤버가 아직 목록에 없으면 자동 등록
    if (!session.members.has(proposal.proposerId)) {
      session.members.set(proposal.proposerId, {
        memberId: proposal.proposerId,
        displayName: proposal.proposerName,
        role: 'MOBILE_NAVIGATOR',
        isOffRoute: false,
        crossTrackDistanceMeters: 0,
        inVoiceCall: false,
        recommendedIntervalMs: 10000,
        consecutiveOffRouteTicks: 0,
        lastAlertAtMs: 0,
        proactiveEnergyWarned: false,
      });
    }

    const participantTokens = await this.mintSessionTokens(session);
    const alert: GroupAlertEvent = {
      eventId: `EVT-${randomUUID().slice(0, 8)}`,
      groupId: session.groupId,
      createdAtMs: Date.now(),
      type: 'NAVIGATOR_ROUTE_PROPOSAL',
      sourceMemberId: proposal.proposerId,
      sourceDisplayName: proposal.proposerName,
      etaDeltaMinutes: -Math.abs(effectiveSavingsMinutes),
      recommendedWaypoint: proposal.waypoint,
      alternativePolyline: candidateRoute.polyline,
      ambientColor: 'CYAN_PROPOSAL',
      aiBriefing: {
        headlineKo: `📬 ${proposal.proposerName}님 [ ${Math.abs(effectiveSavingsMinutes)}분 단축 ] 경로 추천`,
        detailKo:
          proposal.noteKo ||
          `'${proposal.waypoint.name}' 경유 실시간 정체 우회 경로입니다. 수락 시 내비 반영 및 보이스가 연결됩니다.`,
        ttsScriptKo: `${proposal.proposerName}님이 ${Math.abs(effectiveSavingsMinutes)}분 단축 가능한 ${proposal.waypoint.name} 우회 경로를 보냈습니다. 경로를 변경하고 보이스로 대화할까요?`,
      },
      voiceSession: {
        roomName: session.activeVoiceRoomName,
        livekitUrl: this.livekitService.getLiveKitUrl(),
        participantTokens,
        autoConnectOnAccept: true,
      },
      timeoutSeconds: 8,
    };

    session.activeAlert = alert;
    return {
      accepted: true,
      alert,
      snapshot: this.toSnapshot(session),
    };
  }

  /**
   * 운전자의 수락/유지 의사결정 반영 (경유지 동기화 + 보이스톡 활성화 + 앰비언트 라이트 상태 전환)
   */
  resolveAlert(decisionPayload: AlertDecisionPayload): {
    snapshot: GroupSessionSnapshot;
    voiceActive: boolean;
    ambientColor: AmbientAlertColor;
  } | null {
    const session = this.resolveInternalSession(decisionPayload.groupId);
    if (!session) return null;

    const currentAlert = session.activeAlert;
    let ambientColor: AmbientAlertColor = 'NONE';

    if (
      currentAlert &&
      (decisionPayload.decision === 'ACCEPT_AND_CALL' ||
        decisionPayload.decision === 'ACCEPT_ROUTE_ONLY')
    ) {
      if (currentAlert.recommendedWaypoint) {
        session.activeWaypoints.push(currentAlert.recommendedWaypoint);
      }
      if (
        currentAlert.alternativePolyline &&
        currentAlert.alternativePolyline.length >= 2
      ) {
        session.baselinePolyline = currentAlert.alternativePolyline;
        session.baselineDurationSeconds = Math.max(
          300,
          session.baselineDurationSeconds +
            currentAlert.etaDeltaMinutes * 60,
        );
      }
    }

    if (decisionPayload.decision === 'ACCEPT_AND_CALL') {
      session.voiceCallActive = true;
      ambientColor = 'GREEN_VOICE_ACTIVE';
      for (const m of session.members.values()) {
        if (
          m.memberId === decisionPayload.memberId ||
          (currentAlert && m.memberId === currentAlert.sourceMemberId)
        ) {
          m.inVoiceCall = true;
        }
      }
    }

    session.activeAlert = undefined;
    return {
      snapshot: this.toSnapshot(session),
      voiceActive: session.voiceCallActive,
      ambientColor,
    };
  }

  setVoiceCallState(params: {
    groupIdOrCode: string;
    memberId: string;
    inVoiceCall: boolean;
  }): GroupSessionSnapshot | null {
    const session = this.resolveInternalSession(params.groupIdOrCode);
    if (!session) return null;

    const member = session.members.get(params.memberId);
    if (member) {
      member.inVoiceCall = params.inVoiceCall;
    }
    session.voiceCallActive = Array.from(session.members.values()).some(
      (m) => m.inVoiceCall,
    );
    return this.toSnapshot(session);
  }

  async mintMemberVoiceToken(params: {
    groupIdOrCode: string;
    memberId: string;
    displayName: string;
    /** 1:1 방 등. 그룹 방 이름으로 시작하는 방만 허용(다른 그룹 방 토큰 발급 차단) */
    roomName?: string;
  }): Promise<{ roomName: string; livekitUrl: string; token: string } | null> {
    const session = this.resolveInternalSession(params.groupIdOrCode);
    if (!session) return null;
    const base = session.activeVoiceRoomName;
    const roomName =
      params.roomName && (params.roomName === base || params.roomName.startsWith(`${base}-`))
        ? params.roomName
        : base;

    const token = await this.livekitService.createParticipantToken({
      roomName,
      participantIdentity: params.memberId,
      participantName: params.displayName,
    });
    return {
      roomName,
      livekitUrl: this.livekitService.getLiveKitUrl(),
      token,
    };
  }

  /**
   * 휘발성 그룹 세션 영구 파기 (Zero-Trace Privacy 보장)
   */
  destroySession(groupIdOrCode: string): string | null {
    const session = this.resolveInternalSession(groupIdOrCode);
    if (!session) return null;

    const { groupId, inviteCode } = session;
    session.members.clear();
    session.baselinePolyline = [];
    session.activeWaypoints = [];
    session.activeAlert = undefined;

    this.sessions.delete(groupId);
    this.inviteIndex.delete(inviteCode);
    return groupId;
  }

  private resolveInternalSession(
    groupIdOrCode: string,
  ): InternalGroupSession | undefined {
    const direct = this.sessions.get(groupIdOrCode);
    if (direct) return direct;
    const mappedId = this.inviteIndex.get(groupIdOrCode.toUpperCase());
    return mappedId ? this.sessions.get(mappedId) : undefined;
  }

  private async mintSessionTokens(
    session: InternalGroupSession,
  ): Promise<Record<string, string>> {
    const participants = Array.from(session.members.values()).map((m) => ({
      id: m.memberId,
      name: m.displayName,
    }));
    if (participants.length === 0) {
      participants.push({ id: 'CAR-01', name: '1호차' });
    }
    return this.livekitService.createGroupTokenMap(
      session.activeVoiceRoomName,
      participants,
    );
  }

  private generateUniqueInviteCode(): string {
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for (let attempt = 0; attempt < 20; attempt++) {
      let code = '';
      for (let i = 0; i < 6; i++) {
        code += alphabet[Math.floor(Math.random() * alphabet.length)];
      }
      if (!this.inviteIndex.has(code)) return code;
    }
    return randomUUID().slice(0, 6).toUpperCase();
  }

  private toSnapshot(session: InternalGroupSession): GroupSessionSnapshot {
    return {
      groupId: session.groupId,
      inviteCode: session.inviteCode,
      title: session.title,
      origin: { ...session.origin },
      destination: { ...session.destination },
      activeWaypoints: [...session.activeWaypoints],
      baselinePolyline: [...session.baselinePolyline],
      baselineDurationSeconds: session.baselineDurationSeconds,
      baselineDistanceMeters: session.baselineDistanceMeters,
      activeVoiceRoomName: session.activeVoiceRoomName,
      voiceCallActive: session.voiceCallActive,
      members: Array.from(session.members.values()).map((m) => ({
        memberId: m.memberId,
        displayName: m.displayName,
        role: m.role,
        lastTelemetry: m.lastTelemetry,
        isOffRoute: m.isOffRoute,
        crossTrackDistanceMeters: m.crossTrackDistanceMeters,
        inVoiceCall: m.inVoiceCall,
        recommendedIntervalMs: m.recommendedIntervalMs,
      })),
      activeAlert: session.activeAlert,
      createdAtMs: session.createdAtMs,
    };
  }
}
