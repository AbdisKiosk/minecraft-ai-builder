import { LandingFooter, LandingNav, SIGN_UP_HREF } from '../components/Landing';
import { auth0 } from '../lib/server/auth0';

export const dynamic = 'force-dynamic';

/** An isometric stack of blocks, drawn rather than loaded. */
function Blocks() {
  const top = '#6fcf8f', left = '#3d8a5c', right = '#4fa771';
  const cubes = [[0, 2, 0], [1, 2, 0], [2, 2, 0], [0, 2, 1], [2, 2, 1], [0, 2, 2], [1, 2, 2], [2, 2, 2], [1, 1, 1], [1, 0, 1], [0, 1, 0], [2, 1, 2]];
  const order = [...cubes].sort((a, b) => (a[0] + a[2]) - (b[0] + b[2]) || b[1] - a[1]);
  return (
    <svg viewBox="-60 -10 120 110" role="img" aria-label="Blocks being placed">
      {order.map(([x, y, z], n) => {
        const cx = (x - z) * 17, cy = (x + z) * 10 + y * 20;
        return (
          <g key={n} transform={`translate(${cx} ${cy})`} stroke="#14261b" strokeWidth=".6" strokeLinejoin="round">
            <path d="M0 0 17 10 0 20 -17 10z" fill={top} />
            <path d="M-17 10 0 20 0 40 -17 30z" fill={left} />
            <path d="M17 10 0 20 0 40 17 30z" fill={right} />
          </g>
        );
      })}
    </svg>
  );
}

export default async function Home() {
  const session = await auth0.getSession();
  return (
    <div className="landing">
      <LandingNav signedIn={!!session} />

      <section className="hero">
        <div>
          <h1>Let AI build on your Minecraft server.</h1>
          <p className="lead">Tell your AI what to build and watch it appear on your Paper server. Review edits before they land
            and undo anything with one click. Setup takes a few minutes: no ports to open, no config files to edit.</p>
          <div className="cta">
            {session ? <a className="button" href="/dashboard">Open dashboard</a>
              : <a className="button" href={SIGN_UP_HREF}>Get started free</a>}
            <a className="button ghost" href="#how">How it works</a>
          </div>
        </div>
        <div className="hero-art"><Blocks /></div>
      </section>

      <section className="features">
        <div className="card"><h2>Live jobs</h2><p>Every fill, paste and roof the AI makes shows up as it runs, with progress for big edits.</p></div>
        <div className="card"><h2>History you can undo</h2><p>Before and after pictures of each edit, a 3D view, and revert that works even after a restart.</p></div>
        <div className="card"><h2>Review mode</h2><p>Keep the AI&apos;s edits in a draft until you approve them, then place exactly what you previewed.</p></div>
        <div className="card"><h2>Build together</h2><p>Invite friends as admins, builders or viewers with a link. Each person uses their own access token.</p></div>
        <div className="card"><h2>Many servers</h2><p>Staging and live, creative and survival: promote a region from one server to another.</p></div>
        <div className="card"><h2>1.8 to today</h2><p>Works with WorldEdit 6 and 7 and FastAsyncWorldEdit, from Minecraft 1.8 to the newest release.</p></div>
      </section>

      <ol className="steps" id="how">
        <li><span><b>Create an account and name your server.</b> You get a copy of the plugin that&apos;s already set up for it.</span></li>
        <li><span><b>Drop it into your server&apos;s <code>plugins</code> folder</b>, next to WorldEdit, and restart. The page shows
          the moment it connects.</span></li>
        <li><span><b>Connect your AI assistant</b> with one command and ask it to build.</span></li>
      </ol>

      <LandingFooter />
    </div>
  );
}
