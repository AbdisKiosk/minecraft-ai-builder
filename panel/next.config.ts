import type { NextConfig } from 'next';

const config: NextConfig = {
  // Compression would buffer the /api/events stream.
  compress: false,
  poweredByHeader: false,
};

export default config;
