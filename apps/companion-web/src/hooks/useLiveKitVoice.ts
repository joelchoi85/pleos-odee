import { Room, RoomEvent, Track } from 'livekit-client';
import { useCallback, useEffect, useRef, useState } from 'react';

export type VoiceConnectionMode =
  | 'IDLE'
  | 'CONNECTING'
  | 'LIVEKIT_CONNECTED'
  | 'SIGNALING_FALLBACK_ACTIVE';

export function useLiveKitVoice(params: {
  serverHttpBase: string;
  groupId: string;
  memberId: string;
  displayName: string;
  onVoiceStateChanged?: (active: boolean) => void;
}) {
  const [mode, setMode] = useState<VoiceConnectionMode>('IDLE');
  const [micMuted, setMicMuted] = useState(false);
  const [remoteSpeakers, setRemoteSpeakers] = useState<string[]>([]);
  const roomRef = useRef<Room | null>(null);
  const audioContainerRef = useRef<HTMLDivElement | null>(null);

  const connectVoice = useCallback(
    async (preMinted?: { livekitUrl: string; token?: string }, roomName?: string) => {
      // mode(state)는 disconnect 직후 같은 틱에선 이전 값이라 ref로 판단
      if (roomRef.current) return;
      setMode('CONNECTING');

      try {
        let livekitUrl = preMinted?.livekitUrl;
        let token = preMinted?.token;

        if (!livekitUrl || !token) {
          const res = await fetch(
            `${params.serverHttpBase}/api/groups/${encodeURIComponent(params.groupId)}/voice-token`,
            {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                memberId: params.memberId,
                displayName: params.displayName,
                roomName, // 없으면 서버가 그룹 방으로
              }),
            },
          );
          if (!res.ok) throw new Error('Token issue failed');
          const data = (await res.json()) as {
            livekitUrl: string;
            token: string;
          };
          livekitUrl = data.livekitUrl;
          token = data.token;
        }

        const room = new Room({
          adaptiveStream: true,
          audioCaptureDefaults: {
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true,
          },
        });

        room.on(RoomEvent.TrackSubscribed, (track) => {
          if (track.kind === Track.Kind.Audio) {
            const el = track.attach();
            audioContainerRef.current?.appendChild(el);
          }
        });

        room.on(RoomEvent.ActiveSpeakersChanged, (speakers) => {
          setRemoteSpeakers(
            speakers.map((s) => s.name || s.identity).filter(Boolean),
          );
        });

        await Promise.race([
          room.connect(livekitUrl, token),
          new Promise((_, reject) =>
            setTimeout(() => reject(new Error('LiveKit SFU timeout')), 2200),
          ),
        ]);

        await room.localParticipant.setMicrophoneEnabled(true);
        roomRef.current = room;
        setMicMuted(false);
        setMode('LIVEKIT_CONNECTED');
        params.onVoiceStateChanged?.(true);
      } catch {
        // 로컬 Docker LiveKit 미구동 시에도 시연 흐름이 끊기지 않도록 시그널링 보이스 모드로 자동 전환
        setMode('SIGNALING_FALLBACK_ACTIVE');
        setMicMuted(false);
        params.onVoiceStateChanged?.(true);
      }
    },
    [
      mode,
      params.displayName,
      params.groupId,
      params.memberId,
      params.onVoiceStateChanged,
      params.serverHttpBase,
    ],
  );

  const disconnectVoice = useCallback(async () => {
    if (roomRef.current) {
      await roomRef.current.disconnect();
      roomRef.current = null;
    }
    if (audioContainerRef.current) {
      audioContainerRef.current.innerHTML = '';
    }
    setRemoteSpeakers([]);
    setMode('IDLE');
    params.onVoiceStateChanged?.(false);
  }, [params.onVoiceStateChanged]);

  const toggleMute = useCallback(async () => {
    const nextMuted = !micMuted;
    setMicMuted(nextMuted);
    if (roomRef.current) {
      await roomRef.current.localParticipant.setMicrophoneEnabled(!nextMuted);
    }
  }, [micMuted]);

  useEffect(() => {
    return () => {
      roomRef.current?.disconnect();
    };
  }, []);

  return {
    mode,
    isActive:
      mode === 'LIVEKIT_CONNECTED' || mode === 'SIGNALING_FALLBACK_ACTIVE',
    micMuted,
    remoteSpeakers,
    audioContainerRef,
    /** 현재 접속한 LiveKit 방 이름 (거절 신호가 내 통화에 해당하는지 판단용) */
    currentRoomName: () => roomRef.current?.name,
    connectVoice,
    disconnectVoice,
    toggleMute,
  };
}
