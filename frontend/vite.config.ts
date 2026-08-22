import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Same-origin from the browser's perspective, so no CORS config is
      // needed on the backend for local dev. Backend port matches
      // compose.yaml / application.yml's default.
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
