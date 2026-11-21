import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// 개발에서도 /api·/ws를 같은 출처(5173)로 받아 백엔드로 넘긴다.
// 리프레시 쿠키 mj_rt가 SameSite=Strict, Path=/api/auth라 API가 다른 출처면 쿠키가 가지 않는다.
const BACKEND_TARGET = process.env.VITE_DEV_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  // Tailwind CSS v4: 설정 파일(postcss/tailwind.config) 없이 Vite 플러그인 + src/index.css의 @import로 동작
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: BACKEND_TARGET, changeOrigin: false },
      "/ws": { target: BACKEND_TARGET, changeOrigin: false, ws: true },
    },
  },
});
