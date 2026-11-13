import Logo from './Logo';
import { PLUGIN_JAR_HREF } from '../lib/pluginJar';

export const SIGN_UP_HREF = '/auth/login?screen_hint=signup&returnTo=/dashboard';

/** The top bar of the public pages (landing, pricing). */
export function LandingNav({ signedIn }: { signedIn: boolean }) {
  return (
    <nav className="landing-nav">
      <a className="brand" href="/"><Logo />AIBuilder</a>
      <a className="nav-link" href="/pricing">Pricing</a>
      {signedIn ? <a className="button" href="/dashboard">Open dashboard</a> : <>
        <a className="button ghost" href="/auth/login?returnTo=/dashboard">Log in</a>
        <a className="button" href={SIGN_UP_HREF}>Sign up</a>
      </>}
    </nav>
  );
}

export function LandingFooter() {
  return (
    <footer>AIBuilder · Not affiliated with Mojang or Microsoft. · <a href={PLUGIN_JAR_HREF}>Plain plugin jar</a> for setting it up by hand</footer>
  );
}
