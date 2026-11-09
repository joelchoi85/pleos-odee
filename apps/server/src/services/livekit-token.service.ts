import { AccessToken } from 'livekit-server-sdk';

export interface VoiceTokenConfig {
  livekitUrl: string;
  apiKey: string;
  apiSecret: string;
}

export class LiveKitTokenService {
  private readonly config: VoiceTokenConfig;

  constructor(config?: Partial<VoiceTokenConfig>) {
    this.config = {
      livekitUrl:
        config?.livekitUrl || process.env.LIVEKIT_URL || 'ws://localhost:7880',
      apiKey: config?.apiKey || process.env.LIVEKIT_API_KEY || 'devkey',
      apiSecret:
        config?.apiSecret ||
        process.env.LIVEKIT_API_SECRET ||
        'secret_pleos_syncdrive_2026_devkey_32chars',
    };
  }

  getLiveKitUrl(): string {
    return this.config.livekitUrl;
  }

  async createParticipantToken(params: {
    roomName: string;
    participantIdentity: string;
    participantName: string;
  }): Promise<string> {
    const at = new AccessToken(this.config.apiKey, this.config.apiSecret, {
      identity: params.participantIdentity,
      name: params.participantName,
      ttl: '2h',
    });
    at.addGrant({
      roomJoin: true,
      room: params.roomName,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
    });
    return await at.toJwt();
  }

  async createGroupTokenMap(
    roomName: string,
    participants: Array<{ id: string; name: string }>,
  ): Promise<Record<string, string>> {
    const entries = await Promise.all(
      participants.map(async (p) => {
        const jwt = await this.createParticipantToken({
          roomName,
          participantIdentity: p.id,
          participantName: p.name,
        });
        return [p.id, jwt] as const;
      }),
    );
    return Object.fromEntries(entries);
  }
}
