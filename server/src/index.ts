import { serve } from "bun";
import index from "./index.html";

const isDev = process.env.NODE_ENV !== "production";
const devServerHostname = isDev ? process.env.DEV_SERVER_HOSTNAME : undefined;

const server = serve({
  ...(devServerHostname ? { hostname: devServerHostname } : {}),

  routes: {
    // Serve index.html for all unmatched routes.
    "/*": index,
  },

  development: isDev && {
    // Enable browser hot reloading in development
    hmr: true,

    // Echo console logs from the browser to the server
    console: true,
  },
});

console.log(`🚀 Server running at ${server.url}`);
