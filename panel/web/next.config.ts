import type { NextConfig } from 'next';

// The panel jar serves this app as static files (see ../build.gradle), so production is a static export.
// `npm run dev` proxies the data endpoints to a panel running on its default port.
const dev = process.env.NODE_ENV === 'development';
const panel = process.env.PANEL_URL || 'http://127.0.0.1:8080';

const config: NextConfig = dev
  ? {
      // Compression would buffer the /events stream.
      compress: false,
      async rewrites() {
        return [
          { source: '/api/:path*', destination: `${panel}/api/:path*` },
          { source: '/events', destination: `${panel}/events` },
        ];
      },
    }
  : { output: 'export' };

export default config;
