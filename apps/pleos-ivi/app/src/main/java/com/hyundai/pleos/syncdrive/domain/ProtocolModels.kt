package com.hyundai.pleos.syncdrive.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GeoPoint(
    val lat: Double,
    val lng: Double
)

@Serializable
data class NamedGeoPoint(
    val lat: Double,
    val lng: Double,
    val name: String
)

@Serializable
enum class DeviationCategory {
    ACCIDENTAL_MISSED_EXIT,
    INTENTIONAL_PITSTOP_CHARGE_FUEL,
    INTENTIONAL_PITSTOP_REST,
    PROACTIVE_LOW_ENERGY_WARNING
}

@Serializable
enum class PoiCategory {
    EV_CHARGER,
    GAS_STATION,
    REST_AREA,
    PARKING,
    ETC
}

@Serializable
enum class GroupAlertType {
    VEHICLE_DEVIATION,
    NAVIGATOR_ROUTE_PROPOSAL,
    PROACTIVE_PITSTOP,
    /** 앱 내부 전용: 1:1 통화 호출(VOICE_INVITE)을 알림 흐름으로 띄울 때 사용. 서버는 보내지 않음 */
    VOICE_INVITE
}

@Serializable
enum class SessionEndReason {
    ARRIVED_DESTINATION,
    HOST_TERMINATED
}

@Serializable
data class RecommendedWaypoint(
    val name: String,
    val lat: Double,
    val lng: Double,
    val poiCategory: PoiCategory? = null
)

@Serializable
data class LocationTelemetry(
    val lat: Double,
    val lng: Double,
    val heading: Double,
    val speedKmh: Double
)

@Serializable
data class VehicleStateTelemetry(
    val powertrain: PowertrainType,
    val socPercent: Double? = null,
    val fuelPercent: Double? = null,
    val remainingRangeKm: Double? = null,
    val gear: GearState,
    val isBraking: Boolean
)

@Serializable
data class RouteStateTelemetry(
    val activeRouteId: String,
    val etaIso: String
)

@Serializable
data class VehicleTelemetryPayload(
    val groupId: String,
    val vehicleId: String,
    val displayName: String,
    val role: MemberRole = MemberRole.PLEOS_COMPANION,
    val timestampMs: Long,
    val location: LocationTelemetry,
    val vehicleState: VehicleStateTelemetry,
    val routeState: RouteStateTelemetry
)

@Serializable
data class AiBriefingPayload(
    val headlineKo: String,
    val detailKo: String,
    val ttsScriptKo: String
)

@Serializable
data class VoiceSessionPayload(
    val roomName: String,
    val livekitUrl: String,
    val participantTokens: Map<String, String> = emptyMap(),
    val autoConnectOnAccept: Boolean = true
)

@Serializable
data class GroupAlertEvent(
    val eventId: String,
    val groupId: String,
    val createdAtMs: Long,
    val type: GroupAlertType,
    val sourceMemberId: String,
    val sourceDisplayName: String,
    val category: DeviationCategory? = null,
    val etaDeltaMinutes: Double,
    val recommendedWaypoint: RecommendedWaypoint? = null,
    val alternativePolyline: List<GeoPoint>? = null,
    val ambientColor: AmbientAlertColor,
    val aiBriefing: AiBriefingPayload,
    val voiceSession: VoiceSessionPayload,
    val timeoutSeconds: Int = 8
)

@Serializable
data class AlertDecisionPayload(
    val groupId: String,
    val eventId: String,
    val memberId: String,
    val decision: VoiceDecision
)

@Serializable
data class GroupMemberSnapshot(
    val memberId: String,
    val displayName: String,
    val role: MemberRole,
    val lastTelemetry: VehicleTelemetryPayload? = null,
    val isOffRoute: Boolean = false,
    val crossTrackDistanceMeters: Double = 0.0,
    val inVoiceCall: Boolean = false,
    val recommendedIntervalMs: Long = 5_000L
)

@Serializable
data class GroupSessionSnapshot(
    val groupId: String,
    val inviteCode: String,
    val title: String,
    val origin: NamedGeoPoint,
    val destination: NamedGeoPoint,
    val activeWaypoints: List<RecommendedWaypoint> = emptyList(),
    val baselinePolyline: List<GeoPoint> = emptyList(),
    val baselineDurationSeconds: Int = 0,
    val baselineDistanceMeters: Double = 0.0,
    val activeVoiceRoomName: String = "",
    val voiceCallActive: Boolean = false,
    val members: List<GroupMemberSnapshot> = emptyList(),
    val activeAlert: GroupAlertEvent? = null,
    val createdAtMs: Long = 0L
)

// Outbound Client -> Server WebSocket wire DTOs
@Serializable
data class JoinGroupWsEnvelope(
    val type: String = "JOIN_GROUP",
    val groupId: String,
    val memberId: String,
    val displayName: String,
    val role: MemberRole
)

@Serializable
data class TelemetryUpdateWsEnvelope(
    val type: String = "TELEMETRY_UPDATE",
    val payload: VehicleTelemetryPayload
)

@Serializable
data class AlertDecisionWsEnvelope(
    val type: String = "ALERT_DECISION",
    val payload: AlertDecisionPayload
)

@Serializable
data class VoiceInviteWsEnvelope(
    val type: String = "VOICE_INVITE",
    /** 송신 시 필수. 서버 방송본에는 없음 */
    val groupId: String = "",
    val fromMemberId: String,
    val fromDisplayName: String,
    val toMemberId: String,
    val roomName: String
)

@Serializable
data class VoiceStateChangeWsEnvelope(
    val type: String = "VOICE_STATE_CHANGE",
    val groupId: String,
    val memberId: String,
    val inVoiceCall: Boolean
)

@Serializable
data class EndGroupSessionWsEnvelope(
    val type: String = "END_GROUP_SESSION",
    val groupId: String,
    val reason: SessionEndReason
)

// Inbound Server -> Client WebSocket parsed messages
sealed interface ServerWsEvent {
    data class SessionSnapshot(val snapshot: GroupSessionSnapshot) : ServerWsEvent

    data class TelemetryAck(
        val vehicleId: String,
        val nextIntervalMs: Long,
        val crossTrackDistanceMeters: Double,
        val safetyLevel: DrivingSafetyLevel
    ) : ServerWsEvent

    data class GroupAlert(val alert: GroupAlertEvent) : ServerWsEvent

    data class ProposalRejected(
        val proposerId: String,
        val reasonKo: String,
        val etaDeltaMinutes: Double
    ) : ServerWsEvent

    data class AlertResolved(
        val eventId: String,
        val resolvedBy: String,
        val decision: VoiceDecision,
        val voiceActive: Boolean,
        val ambientColor: AmbientAlertColor
    ) : ServerWsEvent

    data class SessionTerminated(
        val groupId: String,
        val reason: SessionEndReason,
        val wipedAtMs: Long
    ) : ServerWsEvent

    data class ErrorMessage(val message: String) : ServerWsEvent

    data class VoiceInvite(
        val isDecline: Boolean,
        val fromMemberId: String,
        val fromDisplayName: String,
        val toMemberId: String,
        val roomName: String
    ) : ServerWsEvent
}

@Serializable
private data class SessionSnapshotWire(
    val type: String,
    val snapshot: GroupSessionSnapshot
)

@Serializable
private data class TelemetryAckWire(
    val type: String,
    val vehicleId: String,
    val nextIntervalMs: Long,
    val crossTrackDistanceMeters: Double = 0.0,
    val safetyLevel: DrivingSafetyLevel = DrivingSafetyLevel.NORMAL_DRIVING
)

@Serializable
private data class GroupAlertWire(
    val type: String,
    val alert: GroupAlertEvent
)

@Serializable
private data class ProposalRejectedWire(
    val type: String,
    val proposerId: String,
    val reasonKo: String,
    val etaDeltaMinutes: Double = 0.0
)

@Serializable
private data class AlertResolvedWire(
    val type: String,
    val eventId: String,
    val resolvedBy: String,
    val decision: VoiceDecision,
    val voiceActive: Boolean,
    val ambientColor: AmbientAlertColor
)

@Serializable
private data class SessionTerminatedWire(
    val type: String,
    val groupId: String,
    val reason: SessionEndReason,
    val wipedAtMs: Long
)

@Serializable
private data class ErrorWire(
    val type: String,
    val message: String
)

object SyncProtocolJson {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun encodeJoinGroup(
        groupId: String,
        memberId: String,
        displayName: String,
        role: MemberRole
    ): String = json.encodeToString(
        JoinGroupWsEnvelope(
            groupId = groupId,
            memberId = memberId,
            displayName = displayName,
            role = role
        )
    )

    fun encodeTelemetryUpdate(payload: VehicleTelemetryPayload): String =
        json.encodeToString(TelemetryUpdateWsEnvelope(payload = payload))

    fun encodeAlertDecision(payload: AlertDecisionPayload): String =
        json.encodeToString(AlertDecisionWsEnvelope(payload = payload))

    fun encodeVoiceStateChange(
        groupId: String,
        memberId: String,
        inVoiceCall: Boolean
    ): String = json.encodeToString(
        VoiceStateChangeWsEnvelope(
            groupId = groupId,
            memberId = memberId,
            inVoiceCall = inVoiceCall
        )
    )

    fun encodeVoiceInvite(envelope: VoiceInviteWsEnvelope): String = json.encodeToString(envelope)

    fun encodeEndGroupSession(groupId: String, reason: SessionEndReason): String =
        json.encodeToString(
            EndGroupSessionWsEnvelope(groupId = groupId, reason = reason)
        )

    fun decodeServerMessage(rawJson: String): ServerWsEvent? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return null
        val messageType = root["type"]?.jsonPrimitive?.content ?: return null

        return runCatching {
            when (messageType) {
                "SESSION_SNAPSHOT" -> {
                    val wire = json.decodeFromString<SessionSnapshotWire>(rawJson)
                    ServerWsEvent.SessionSnapshot(wire.snapshot)
                }
                "TELEMETRY_ACK" -> {
                    val wire = json.decodeFromString<TelemetryAckWire>(rawJson)
                    ServerWsEvent.TelemetryAck(
                        vehicleId = wire.vehicleId,
                        nextIntervalMs = clampTelemetryIntervalMs(wire.nextIntervalMs),
                        crossTrackDistanceMeters = wire.crossTrackDistanceMeters,
                        safetyLevel = wire.safetyLevel
                    )
                }
                "GROUP_ALERT" -> {
                    val wire = json.decodeFromString<GroupAlertWire>(rawJson)
                    ServerWsEvent.GroupAlert(wire.alert)
                }
                "PROPOSAL_REJECTED" -> {
                    val wire = json.decodeFromString<ProposalRejectedWire>(rawJson)
                    ServerWsEvent.ProposalRejected(
                        proposerId = wire.proposerId,
                        reasonKo = wire.reasonKo,
                        etaDeltaMinutes = wire.etaDeltaMinutes
                    )
                }
                "ALERT_RESOLVED" -> {
                    val wire = json.decodeFromString<AlertResolvedWire>(rawJson)
                    ServerWsEvent.AlertResolved(
                        eventId = wire.eventId,
                        resolvedBy = wire.resolvedBy,
                        decision = wire.decision,
                        voiceActive = wire.voiceActive,
                        ambientColor = wire.ambientColor
                    )
                }
                "SESSION_TERMINATED" -> {
                    val wire = json.decodeFromString<SessionTerminatedWire>(rawJson)
                    ServerWsEvent.SessionTerminated(
                        groupId = wire.groupId,
                        reason = wire.reason,
                        wipedAtMs = wire.wipedAtMs
                    )
                }
                "VOICE_INVITE", "VOICE_DECLINE" -> {
                    val wire = json.decodeFromString<VoiceInviteWsEnvelope>(rawJson)
                    ServerWsEvent.VoiceInvite(messageType == "VOICE_DECLINE", wire.fromMemberId, wire.fromDisplayName, wire.toMemberId, wire.roomName)
                }
                "ERROR" -> {
                    val wire = json.decodeFromString<ErrorWire>(rawJson)
                    ServerWsEvent.ErrorMessage(wire.message)
                }
                else -> null
            }
        }.getOrNull()
    }
}
