import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    host: true,
    // 폰 테스트용 cloudflared 임시 터널 주소 허용
    allowedHosts: ['.trycloudflare.com'],
  },
});
