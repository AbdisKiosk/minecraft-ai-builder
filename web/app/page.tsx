import Logo from '../components/Logo';
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
      <nav className="landing-nav">
        <a className="brand" href="/"><Logo />AIBuilder</a>
        {session ? <a className="button" href="/dashboard">Open dashboard</a> : <>
          <a className="button ghost" href="/auth/login?returnTo=/dashboard">Log in</a>
          <a className="button" href="/auth/login?screen_hint=signup&returnTo=/dashboard">Sign up</a>
        </>}
      </nav>

      <section className="hero">
        <div>
          <h1>Let Claude build on your Minecraft server.</h1>
          <p className="lead">AIBuilder connects Claude Code to WorldEdit on your Paper server. Watch builds happen live, review
            edits before they land, and roll back anything with one click. Your server opens no port.</p>
          <div className="cta">
            {session ? <a className="button" href="/dashboard">Open dashboard</a>
              : <a className="button" href="/auth/login?screen_hint=signup&returnTo=/dashboard">Create a free account</a>}
            <a className="button ghost" href="#how">How it works</a>
          </div>
        </div>
        <div className="hero-art"><Blocks /></div>
      </section>

      <section className="features">
        <div className="card"><h2>Live jobs</h2><p>Every fill, paste and roof Claude makes shows up as it runs, with progress for big edits.</p></div>
        <div className="card"><h2>History you can undo</h2><p>Before and after pictures of each edit, a 3D view, and revert that works even after a restart.</p></div>
        <div className="card"><h2>Review mode</h2><p>Keep Claude&apos;s edits in a draft until you approve them, then place exactly what you previewed.</p></div>
        <div className="card"><h2>Build together</h2><p>Invite friends as admins, builders or viewers with a link. Each person uses their own access token.</p></div>
        <div className="card"><h2>Many servers</h2><p>Staging and live, creative and survival: promote a region from one server to another.</p></div>
        <div className="card"><h2>1.8 to today</h2><p>Works with WorldEdit 6 and 7 and FastAsyncWorldEdit, from Minecraft 1.8 to the newest release.</p></div>
      </section>

      <ol className="steps" id="how">
        <li><span><b>Create an account</b> and add your server. You get two lines for the plugin&apos;s config.</span></li>
        <li><span><b>Install the AIBuilder plugin</b> next to WorldEdit, paste the lines and restart. The server connects out to us.</span></li>
        <li><span><b>Make an access token</b> and point Claude Code at your server&apos;s address. Ask it to build.</span></li>
      </ol>

      <footer>AIBuilder · Not affiliated with Mojang or Microsoft.</footer>
    </div>
  );
}
