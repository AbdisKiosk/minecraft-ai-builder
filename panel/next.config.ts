import type { NextConfig } from 'next';

// Sent with every page and API route Next answers (server.ts answers /s/ and /relay itself). No page is meant to be
// framed: the dashboard's one-click revert and approve must not be clickable through someone else's page.
const SECURITY_HEADERS = [
  // Browsers ignore it over plain http, so it is harmless in development.
  { key: 'Strict-Transport-Security', value: 'max-age=31536000' },
  { key: 'Content-Security-Policy', value: "frame-ancestors 'none'" },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
];

const config: NextConfig = {
  // Compression would buffer the /api/events stream.
  compress: false,
  poweredByHeader: false,
  async headers() {
    return [{ source: '/:path*', headers: SECURITY_HEADERS }];
  },
};

export default config;
