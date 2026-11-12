import type { NextRequest } from 'next/server';
import { auth0 } from './lib/server/auth0';

// Auth0 mounts /auth/login, /auth/logout, /auth/callback, … here and keeps the session cookie rolling.
export async function proxy(request: NextRequest) {
  return await auth0.middleware(request);
}

export const config = {
  matcher: ['/((?!_next/static|_next/image|favicon.ico|robots.txt|relay$).*)'],
};
