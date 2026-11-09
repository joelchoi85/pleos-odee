import cors from '@fastify/cors';
import websocket from '@fastify/websocket';
import {
  ClientWsMessageSchema,
  NavigatorRouteProposalPayloadSchema,
  type ServerWsMessage,
} from '@odee/shared-types';
import Fastify, { type FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import type { MapRoutingProvider } from './providers/map-routing.interface.js';
import type { DeviationAiService } from './services/deviation-ai.service.js';
import {
  EphemeralSessionService,
  type CreateGroupOptions,
} from './services/ephemeral-session.service.js';
import type { LiveKitTokenService } from './services/livekit-token.service.js';

export interface BuildAppOptions {
  mapProvider?: MapRoutingProvider;
  aiService?: DeviationAiService;
  livekitService?: LiveKitTokenService;
  seedDemoSession?: boolean;
}

export async function buildApp(
  opts: BuildAppOptions = {},
): Promise<{
  app: FastifyInstance;
  sessionService: EphemeralSessionService;
}> {
  const app = Fastify({ logger: false });
  await app.register(cors, { origin: true });
  await app.register(websocket);

  const sessionService = new EphemeralSessionService({
    mapProvider: opts.mapProvider,
    aiService: opts.aiService,
    livekitService: opts.livekitService,
  });

  if (opts.seedDemoSession !== false) {
    await sessionService.createSession({
      groupId: 'GRP-PLEOS-DEMO',
      title: '판교 → 현대모터스튜디오 서울 패밀리 투어',
      hostId: 'HYUNDAI-IONIQ5-01',
      hostName: '1호차 (아이오닉 5 · Pleos IVI)',
    });
  }

  const roomSockets = new Map<string, Set<WebSocket>>();

  function attachSocketToGroup(groupId: string, socket: WebSocket): void {
    let set = roomSockets.get(groupId);
    if (!set) {
      set = new Set();
      roomSockets.set(groupId, set);
    }
    set.add(socket);
  }

  function broadcastToGroup(groupId: string, msg: ServerWsMessage): void {
    const sockets = roomSockets.get(groupId);
    if (!sockets) return;
    const raw = JSON.stringify(msg);
    for (const sock of sockets) {
      if (sock.readyState === 1) {
        sock.send(raw);
      }
    }
  }

  function sendJson(socket: WebSocket, msg: ServerWsMessage): void {
    if (socket.readyState === 1) {
      socket.send(JSON.stringify(msg));
    }
  }

  app.get('/health', async () => ({
    status: 'ok',
    activeSessions: sessionService.getActiveSessionCount(),
    timestamp: new Date().toISOString(),
  }));

  app.get('/api/groups', async () => ({
    sessions: sessionService.listSessions(),
  }));

  app.post<{ Body: CreateGroupOptions }>('/api/groups', async (req, reply) => {
    const snapshot = await sessionService.createSession(req.body ?? {});
    return reply.code(201).send(snapshot);
  });

  app.get<{ Params: { id: string } }>('/api/groups/:id', async (req, reply) => {
    const snapshot = sessionService.getSession(req.params.id);
    if (!snapshot) {
      return reply.code(404).send({ error: 'Group session not found' });
    }
    return snapshot;
  });

  app.post<{
    Params: { id: string };
    Body: { memberId: string; displayName: string; roomName?: string };
  }>('/api/groups/:id/voice-token', async (req, reply) => {
    const minted = await sessionService.mintMemberVoiceToken({
      groupIdOrCode: req.params.id,
      memberId: req.body.memberId,
      displayName: req.body.displayName,
      roomName: req.body.roomName,
    });
    if (!minted) {
      return reply.code(404).send({ error: 'Group session not found' });
    }
    return minted;
  });

  app.post<{ Params: { id: string }; Body: unknown }>(
    '/api/groups/:id/propose-route',
    async (req, reply) => {
      const parsed = NavigatorRouteProposalPayloadSchema.safeParse({
        ...(typeof req.body === 'object' && req.body ? req.body : {}),
        groupId: req.params.id,
      });
      if (!parsed.success) {
        return reply.code(400).send({ error: parsed.error.message });
      }
      const result = await sessionService.evaluateNavigatorProposal(parsed.data);
      if (!result) {
        return reply.code(404).send({ error: 'Group session not found' });
      }
      if (!result.accepted) {
        return reply.code(200).send(result);
      }
      broadcastToGroup(result.snapshot.groupId, {
        type: 'GROUP_ALERT',
        alert: result.alert,
      });
      broadcastToGroup(result.snapshot.groupId, {
        type: 'SESSION_SNAPSHOT',
        snapshot: result.snapshot,
      });
      return reply.code(200).send(result);
    },
  );

  app.delete<{ Params: { id: string } }>(
    '/api/groups/:id',
    async (req, reply) => {
      const wipedGroupId = sessionService.destroySession(req.params.id);
      if (!wipedGroupId) {
        return reply.code(404).send({ error: 'Group session not found' });
      }
      broadcastToGroup(wipedGroupId, {
        type: 'SESSION_TERMINATED',
        groupId: wipedGroupId,
        reason: 'HOST_TERMINATED',
        wipedAtMs: Date.now(),
      });
      roomSockets.delete(wipedGroupId);
      return { wiped: true, groupId: wipedGroupId };
    },
  );

  app.get('/ws', { websocket: true }, (socket) => {
    let boundGroupId: string | undefined;

    socket.on('message', async (rawBuf) => {
      try {
        const json = JSON.parse(rawBuf.toString());
        const parsed = ClientWsMessageSchema.safeParse(json);
        if (!parsed.success) {
          sendJson(socket, {
            type: 'ERROR',
            message: `Invalid message schema: ${parsed.error.issues[0]?.message ?? 'unknown'}`,
          });
          return;
        }

        const msg = parsed.data;
        switch (msg.type) {
          case 'JOIN_GROUP': {
            const snapshot = sessionService.joinSession({
              groupIdOrCode: msg.groupId,
              memberId: msg.memberId,
              displayName: msg.displayName,
              role: msg.role,
            });
            if (!snapshot) {
              sendJson(socket, {
                type: 'ERROR',
                message: `Group '${msg.groupId}' not found`,
              });
              return;
            }
            boundGroupId = snapshot.groupId;
            attachSocketToGroup(snapshot.groupId, socket);
            broadcastToGroup(snapshot.groupId, {
              type: 'SESSION_SNAPSHOT',
              snapshot,
            });
            break;
          }

          case 'TELEMETRY_UPDATE': {
            const outcome = await sessionService.ingestTelemetry(msg.payload);
            if (!outcome) {
              sendJson(socket, {
                type: 'ERROR',
                message: `Group '${msg.payload.groupId}' not found`,
              });
              return;
            }
            boundGroupId = outcome.snapshot.groupId;
            attachSocketToGroup(outcome.snapshot.groupId, socket);

            sendJson(socket, {
              type: 'TELEMETRY_ACK',
              vehicleId: msg.payload.vehicleId,
              nextIntervalMs: outcome.nextIntervalMs,
              crossTrackDistanceMeters: outcome.crossTrackDistanceMeters,
              safetyLevel: outcome.safetyLevel,
            });

            broadcastToGroup(outcome.snapshot.groupId, {
              type: 'SESSION_SNAPSHOT',
              snapshot: outcome.snapshot,
            });

            if (outcome.triggeredAlert) {
              broadcastToGroup(outcome.snapshot.groupId, {
                type: 'GROUP_ALERT',
                alert: outcome.triggeredAlert,
              });
            }
            break;
          }

          case 'NAVIGATOR_PROPOSE_ROUTE': {
            const evalRes = await sessionService.evaluateNavigatorProposal(
              msg.payload,
            );
            if (!evalRes) {
              sendJson(socket, {
                type: 'ERROR',
                message: 'Group session not found',
              });
              return;
            }
            if (!evalRes.accepted) {
              sendJson(socket, {
                type: 'PROPOSAL_REJECTED',
                proposerId: msg.payload.proposerId,
                reasonKo: evalRes.reasonKo,
                etaDeltaMinutes: evalRes.etaDeltaMinutes,
              });
              return;
            }
            broadcastToGroup(evalRes.snapshot.groupId, {
              type: 'GROUP_ALERT',
              alert: evalRes.alert,
            });
            broadcastToGroup(evalRes.snapshot.groupId, {
              type: 'SESSION_SNAPSHOT',
              snapshot: evalRes.snapshot,
            });
            break;
          }

          case 'ALERT_DECISION': {
            const resolved = sessionService.resolveAlert(msg.payload);
            if (!resolved) return;
            broadcastToGroup(resolved.snapshot.groupId, {
              type: 'ALERT_RESOLVED',
              eventId: msg.payload.eventId,
              resolvedBy: msg.payload.memberId,
              decision: msg.payload.decision,
              voiceActive: resolved.voiceActive,
              ambientColor: resolved.ambientColor,
            });
            broadcastToGroup(resolved.snapshot.groupId, {
              type: 'SESSION_SNAPSHOT',
              snapshot: resolved.snapshot,
            });
            break;
          }

          case 'VOICE_STATE_CHANGE': {
            const snapshot = sessionService.setVoiceCallState({
              groupIdOrCode: msg.groupId,
              memberId: msg.memberId,
              inVoiceCall: msg.inVoiceCall,
            });
            if (snapshot) {
              broadcastToGroup(snapshot.groupId, {
                type: 'SESSION_SNAPSHOT',
                snapshot,
              });
            }
            break;
          }

          case 'VOICE_INVITE':
          case 'VOICE_DECLINE': {
            const session = sessionService.getSession(msg.groupId);
            // 그룹 방(toMemberId '*') 또는 그룹 방 이름 + '-' 1:1 방만 허용 (다른 그룹 방으로 유인 차단)
            const base = session?.activeVoiceRoomName;
            if (!session || !(msg.roomName === base || msg.roomName.startsWith(`${base}-`))) {
              sendJson(socket, { type: 'ERROR', message: 'Invalid voice invite' });
              return;
            }
            broadcastToGroup(session.groupId, {
              type: msg.type,
              fromMemberId: msg.fromMemberId,
              fromDisplayName: msg.fromDisplayName,
              toMemberId: msg.toMemberId,
              roomName: msg.roomName,
            });
            break;
          }

          case 'END_GROUP_SESSION': {
            const wipedGroupId = sessionService.destroySession(msg.groupId);
            if (wipedGroupId) {
              broadcastToGroup(wipedGroupId, {
                type: 'SESSION_TERMINATED',
                groupId: wipedGroupId,
                reason: msg.reason,
                wipedAtMs: Date.now(),
              });
              roomSockets.delete(wipedGroupId);
            }
            break;
          }
        }
      } catch (err) {
        sendJson(socket, {
          type: 'ERROR',
          message: err instanceof Error ? err.message : 'Unexpected server error',
        });
      }
    });

    socket.on('close', () => {
      if (boundGroupId) {
        roomSockets.get(boundGroupId)?.delete(socket);
      }
    });
  });

  return { app, sessionService };
}
