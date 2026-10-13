import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  // Tailwind CSS v4: 설정 파일(postcss/tailwind.config) 없이 Vite 플러그인 + src/index.css의 @import로 동작
  plugins: [react(), tailwindcss()],
  server: { port: 5173 },
});
