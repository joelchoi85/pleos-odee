import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { buildApp } from './app.js';

// 루트 .env 로드 (LiveKit·Kakao·Gemini 키). 없으면 각 서비스의 개발용 기본값/폴백 사용
const rootEnv = fileURLToPath(new URL('../../../.env', import.meta.url));
if (existsSync(rootEnv)) process.loadEnvFile(rootEnv);

const PORT = Number(process.env.PORT || 4000);
const HOST = process.env.HOST || '0.0.0.0';

async function main() {
  const { app } = await buildApp();
  await app.listen({ port: PORT, host: HOST });
  console.log(
    `[오디 (Odee) Server] Listening on http://${HOST}:${PORT} (WebSocket: ws://localhost:${PORT}/ws)`,
  );
}

main().catch((err) => {
  console.error('Failed to start 오디 (Odee) server:', err);
  process.exit(1);
});
