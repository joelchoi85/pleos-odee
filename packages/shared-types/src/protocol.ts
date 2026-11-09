import { z } from 'zod';

export const GeoPointSchema = z.object({
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
});
export type GeoPoint = z.infer<typeof GeoPointSchema>;

export const DrivingSafetyLevelSchema = z.enum([
  'STATIONARY',
  'NORMAL_DRIVING',
  'HIGH_FOCUS',
]);
export type DrivingSafetyLevel = z.infer<typeof DrivingSafetyLevelSchema>;

export const AmbientAlertColorSchema = z.enum([
  'NONE',
  'AMBER_DEVIATION',
  'CYAN_PROPOSAL',
  'GREEN_VOICE_ACTIVE',
]);
export type AmbientAlertColor = z.infer<typeof AmbientAlertColorSchema>;

export const PowertrainTypeSchema = z.enum(['EV', 'ICE', 'HEV']);
export type PowertrainType = z.infer<typeof PowertrainTypeSchema>;

export const GearStateSchema = z.enum(['P', 'R', 'N', 'D']);
export type GearState = z.infer<typeof GearStateSchema>;

export const MemberRoleSchema = z.enum([
  'PLEOS_HOST',
  'PLEOS_COMPANION',
  'MOBILE_DRIVER',
  'MOBILE_NAVIGATOR',
]);
export type MemberRole = z.infer<typeof MemberRoleSchema>;

export const VehicleTelemetryPayloadSchema = z.object({
  groupId: z.string().min(1),
  vehicleId: z.string().min(1),
  displayName: z.string().min(1),
  role: MemberRoleSchema.default('PLEOS_COMPANION'),
  timestampMs: z.number().int().positive(),
  location: z.object({
    lat: z.number().min(-90).max(90),
    lng: z.number().min(-180).max(180),
    heading: z.number().min(0).max(360),
    speedKmh: z.number().min(0).max(300),
  }),
  vehicleState: z.object({
    powertrain: PowertrainTypeSchema,
    socPercent: z.number().min(0).max(100).optional(),
    fuelPercent: z.number().min(0).max(100).optional(),
    remainingRangeKm: z.number().min(0).optional(),
    gear: GearStateSchema,
    isBraking: z.boolean(),
  }),
  routeState: z.object({
    activeRouteId: z.string().min(1),
    etaIso: z.string(),
  }),
});
export type VehicleTelemetryPayload = z.infer<typeof VehicleTelemetryPayloadSchema>;

export const DeviationCategorySchema = z.enum([
  'ACCIDENTAL_MISSED_EXIT',
  'INTENTIONAL_PITSTOP_CHARGE_FUEL',
  'INTENTIONAL_PITSTOP_REST',
  'PROACTIVE_LOW_ENERGY_WARNING',
]);
export type DeviationCategory = z.infer<typeof DeviationCategorySchema>;

export const PoiCategorySchema = z.enum([
  'EV_CHARGER',
  'GAS_STATION',
  'REST_AREA',
  'PARKING',
  'ETC',
]);
export type PoiCategory = z.infer<typeof PoiCategorySchema>;

export const RecommendedWaypointSchema = z.object({
  name: z.string().min(1),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  poiCategory: PoiCategorySchema.optional(),
});
export type RecommendedWaypoint = z.infer<typeof RecommendedWaypointSchema>;

export const GroupAlertEventSchema = z.object({
  eventId: z.string().min(1),
  groupId: z.string().min(1),
  createdAtMs: z.number().int().positive(),
  type: z.enum([
    'VEHICLE_DEVIATION',
    'NAVIGATOR_ROUTE_PROPOSAL',
    'PROACTIVE_PITSTOP',
  ]),
  sourceMemberId: z.string().min(1),
  sourceDisplayName: z.string().min(1),
  category: DeviationCategorySchema.optional(),
  etaDeltaMinutes: z.number(), // Negative = faster (-8), Positive = extra time (+5)
  recommendedWaypoint: RecommendedWaypointSchema.optional(),
  alternativePolyline: z.array(GeoPointSchema).optional(),
  ambientColor: AmbientAlertColorSchema,
  aiBriefing: z.object({
    headlineKo: z.string().min(1),
    detailKo: z.string().min(1),
    ttsScriptKo: z.string().min(1),
  }),
  voiceSession: z.object({
    roomName: z.string().min(1),
    livekitUrl: z.string().min(1),
    participantTokens: z.record(z.string(), z.string()),
    autoConnectOnAccept: z.boolean(),
  }),
  timeoutSeconds: z.number().int().positive(),
});
export type GroupAlertEvent = z.infer<typeof GroupAlertEventSchema>;

export const NavigatorRouteProposalPayloadSchema = z.object({
  groupId: z.string().min(1),
  proposerId: z.string().min(1),
  proposerName: z.string().min(1),
  waypoint: RecommendedWaypointSchema,
  claimedSavingsMinutes: z.number().optional(),
  noteKo: z.string().max(120).optional(),
});
export type NavigatorRouteProposalPayload = z.infer<
  typeof NavigatorRouteProposalPayloadSchema
>;

export const AlertDecisionPayloadSchema = z.object({
  groupId: z.string().min(1),
  eventId: z.string().min(1),
  memberId: z.string().min(1),
  decision: z.enum(['ACCEPT_AND_CALL', 'ACCEPT_ROUTE_ONLY', 'KEEP_ROUTE']),
});
export type AlertDecisionPayload = z.infer<typeof AlertDecisionPayloadSchema>;

export interface GroupMemberSnapshot {
  memberId: string;
  displayName: string;
  role: MemberRole;
  lastTelemetry?: VehicleTelemetryPayload;
  isOffRoute: boolean;
  crossTrackDistanceMeters: number;
  inVoiceCall: boolean;
  recommendedIntervalMs: number;
}

export interface GroupSessionSnapshot {
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
  members: GroupMemberSnapshot[];
  activeAlert?: GroupAlertEvent;
  createdAtMs: number;
}

export const ClientWsMessageSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('JOIN_GROUP'),
    groupId: z.string().min(1),
    memberId: z.string().min(1),
    displayName: z.string().min(1),
    role: MemberRoleSchema,
  }),
  z.object({
    type: z.literal('TELEMETRY_UPDATE'),
    payload: VehicleTelemetryPayloadSchema,
  }),
  z.object({
    type: z.literal('NAVIGATOR_PROPOSE_ROUTE'),
    payload: NavigatorRouteProposalPayloadSchema,
  }),
  z.object({
    type: z.literal('ALERT_DECISION'),
    payload: AlertDecisionPayloadSchema,
  }),
  z.object({
    type: z.literal('VOICE_STATE_CHANGE'),
    groupId: z.string().min(1),
    memberId: z.string().min(1),
    inVoiceCall: z.boolean(),
  }),
  z.object({
    // 1:1 호출 거절(무응답 포함). from=거절한 사람, to=원래 발신자
    type: z.enum(['VOICE_INVITE', 'VOICE_DECLINE']),
    groupId: z.string().min(1),
    fromMemberId: z.string().min(1),
    fromDisplayName: z.string().min(1),
    toMemberId: z.string().min(1),
    roomName: z.string().min(1),
  }),
  z.object({
    type: z.literal('END_GROUP_SESSION'),
    groupId: z.string().min(1),
    reason: z.enum(['ARRIVED_DESTINATION', 'HOST_TERMINATED']),
  }),
]);
export type ClientWsMessage = z.infer<typeof ClientWsMessageSchema>;

export type ServerWsMessage =
  | {
      type: 'SESSION_SNAPSHOT';
      snapshot: GroupSessionSnapshot;
    }
  | {
      type: 'TELEMETRY_ACK';
      vehicleId: string;
      nextIntervalMs: number;
      crossTrackDistanceMeters: number;
      safetyLevel: DrivingSafetyLevel;
    }
  | {
      type: 'GROUP_ALERT';
      alert: GroupAlertEvent;
    }
  | {
      type: 'PROPOSAL_REJECTED';
      proposerId: string;
      reasonKo: string;
      etaDeltaMinutes: number;
    }
  | {
      type: 'ALERT_RESOLVED';
      eventId: string;
      resolvedBy: string;
      decision: AlertDecisionPayload['decision'];
      voiceActive: boolean;
      ambientColor: AmbientAlertColor;
    }
  | {
      type: 'SESSION_TERMINATED';
      groupId: string;
      reason: 'ARRIVED_DESTINATION' | 'HOST_TERMINATED';
      wipedAtMs: number;
    }
  | {
      /** 통화 호출. 그룹 전체에 방송되며 toMemberId 본인만 처리. '*'는 그룹 통화(발신자 제외 전원) */
      type: 'VOICE_INVITE' | 'VOICE_DECLINE';
      fromMemberId: string;
      fromDisplayName: string;
      toMemberId: string;
      roomName: string;
    }
  | {
      type: 'ERROR';
      message: string;
    };

/**
 * Pleos Vehicle SDK (Speed / Gear / Brake) 기반 적응형 주행 안전 등급 산출
 */
export function deriveDrivingSafetyLevel(params: {
  speedKmh: number;
  gear: GearState;
  isBraking: boolean;
}): DrivingSafetyLevel {
  if (params.speedKmh <= 1 && params.gear === 'P') {
    return 'STATIONARY';
  }
  if (params.speedKmh >= 60 || params.isBraking) {
    return 'HIGH_FOCUS';
  }
  return 'NORMAL_DRIVING';
}
