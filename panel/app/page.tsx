import { LandingFooter, LandingNav, SIGN_UP_HREF } from '../components/Landing';
import { PLANS, PRO_INCLUDES_STUDIO, limitsOf, type Limits } from '../lib/plans';
import { auth0 } from '../lib/server/auth0';

export const dynamic = 'force-dynamic';

/** An isometric stack of blocks, drawn rather than loaded. */
function Blocks() {
  const top = '#6fcf8f', left = '#3d8a5c', right = '#4fa771';
  const cubes = [[0, 2, 0], [1, 2, 0], [2, 2, 0], [0, 2, 1], [2, 2, 1], [0, 2, 2], [1, 2, 2], [2, 2, 2], [1, 1, 1], [1, 0, 1], [0, 1, 0], [2, 1, 2]];
  const order = [...cubes].sort((a, b) => (a[0] + a[2]) - (b[0] + b[2]) || b[1] - a[1]);
  return (
    <svg viewBox="-56 14 112 112" role="img" aria-label="Blocks being placed">
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

/** The plan a gated feature starts on today, as a badge; nothing when Free has it. The numbers and gates are lib/plans.ts. */
function PlanBadge({ feature }: { feature: keyof Limits }) {
  const plan = (['free', 'pro', 'studio'] as const).find(id => limitsOf(id)[feature]);
  return plan && plan !== 'free' ? <span className="badge running">{PLANS[plan].name}</span> : null;
}

export default async function Home() {
  const session = await auth0.getSession();
  const { free, pro } = PLANS;
  const start = session ? <a className="button" href="/dashboard">Open dashboard</a>
    : <a className="button" href={SIGN_UP_HREF}>Get started free</a>;
  return (
    <div className="landing">
      <LandingNav signedIn={!!session} />

      <section className="hero">
        <div>
          <h1>Describe it. Watch it get built on your Minecraft server.</h1>
          <p className="lead">AIBuilder gives an AI real building tools on your own server: it places the blocks, checks
            its work and fixes what looks wrong. You preview every build before it lands and undo any of it with one
            click.</p>
          <div className="cta">
            {start}
            <a className="button ghost" href="/pricing">See pricing</a>
          </div>
          <ul className="hero-points">
            <li>Free plan, no card needed</li>
            <li>Set up in a few minutes</li>
            <li>No ports to open</li>
          </ul>
        </div>
        <div className="hero-art"><Blocks /></div>
      </section>

      <section className="band">
        <div className="section-head">
          <h2>Three ways to build</h2>
          <p>Use whichever fits. They share the same tools, the same limits and the same undo.</p>
        </div>
        <div className="features">
          <div className="card way">
            <span className="badge running">New</span>
            <h3>Chat in your browser</h3>
            <p>Open your server&apos;s page, type what you want and watch the AI build it, step by step. Nothing to
              install. Bring an Anthropic or OpenRouter API key and pick from Claude or hundreds of other models.</p>
          </div>
          <div className="card way">
            <h3>Your own AI assistant</h3>
            <p>Connect Claude Code, or any assistant that speaks MCP, with one command. It builds from your terminal
              with everything it already knows about your project.</p>
          </div>
          <div className="card way">
            <h3>Build scripts <PlanBadge feature="restApi" /></h3>
            <p>Drive the same tools from code through the REST API and the Python client: generate a city block, a
              maze or a whole spawn from a script.</p>
          </div>
        </div>
      </section>

      <section className="band">
        <div className="section-head">
          <h2>You stay in control of your world</h2>
          <p>An AI with WorldEdit sounds risky. This is why it isn&apos;t.</p>
        </div>
        <div className="features">
          <div className="card"><h3>Review before it lands <PlanBadge feature="review" /></h3><p>Keep the AI&apos;s edits in a draft that only
            you see, look at it in 3D, then approve it and get exactly what you previewed. Or reject it.</p></div>
          <div className="card"><h3>Undo anything</h3><p>Every edit is kept with before and after pictures and a 3D
            view. Revert one with a click, even after a restart.</p></div>
          <div className="card"><h3>Respects WorldGuard <PlanBadge feature="protect" /></h3><p>The AI can&apos;t build where the player it
            acts for can&apos;t. Point it at a region by name instead of typing coordinates.</p></div>
          <div className="card"><h3>Never takes the server down</h3><p>Block limits and a memory check stop an edit
            that is too big before it starts, and big edits run in steps your players won&apos;t feel.</p></div>
          <div className="card"><h3>Roles for your team</h3><p>Invite friends as admins, builders or viewers with a
            link. Everyone builds with their own access token, which you can revoke at any time.</p></div>
          <div className="card"><h3>It learns your taste</h3><p>Reject a build and say why, or fix it by hand. The AI
            reads those lessons at the start of every session, so it gets it right next time.</p></div>
        </div>
      </section>

      <section className="band">
        <div className="section-head">
          <h2>Everything around the build</h2>
          <p>A panel for the whole server, not only a chat box.</p>
        </div>
        <div className="features">
          <div className="card"><h3>Live jobs</h3><p>Every fill, paste and roof shows up as it runs, with progress
            for the big ones.</p></div>
          <div className="card"><h3>World map</h3><p>A top-down map of each world with spawn, online players and the
            areas you marked in game. Viewing it never loads a chunk.</p></div>
          <div className="card"><h3>Asset library <PlanBadge feature="assets" /></h3><p>Save a window, a tree or a whole house once, from the
            map, from the game or from a <code>.schem</code> file. The AI places it by name, on any of your
            servers.</p></div>
          <div className="card"><h3>Staging to live <PlanBadge feature="promote" /></h3><p>Build on a test server and promote the finished
            region to the live one, as a draft you approve there.</p></div>
          <div className="card"><h3>Built to look good</h3><p>Roofs, trees, lighting, doors and beds are generated on
            the server, and the AI checks its build for floating blocks and gaps before it calls it done.</p></div>
          <div className="card"><h3>1.8 to today</h3><p>Paper and Spigot with WorldEdit 6 or 7 or FastAsyncWorldEdit,
            from Minecraft 1.8 to the newest release, on Java 8 and up.</p></div>
        </div>
      </section>

      <section className="band" id="how">
        <div className="section-head">
          <h2>Running in three steps</h2>
          <p>No port forwarding and no config files: the plugin connects out to the panel by itself.</p>
        </div>
        <ol className="steps">
          <li><span><b>Create an account and name your server.</b> You get a copy of the plugin that&apos;s already set up for it.</span></li>
          <li><span><b>Drop it into your server&apos;s <code>plugins</code> folder</b>, next to WorldEdit, and restart. The page shows
            the moment it connects.</span></li>
          <li><span><b>Ask for a build.</b> Chat in the browser, or connect your AI assistant with one command.</span></li>
        </ol>
      </section>

      <section className="band">
        <div className="section-head">
          <h2>Questions</h2>
        </div>
        <div className="faq">
          <details>
            <summary>Is the AI included?</summary>
            <p>No, you bring your own, so you pay the AI provider directly and nothing on top. Claude Code works with
              a Claude subscription. The chat in the browser needs an API key from Anthropic or OpenRouter, which
              bills you for what you use.</p>
          </details>
          <details>
            <summary>Can it wreck my world?</summary>
            <p>Every edit is recorded and can be reverted. With review mode on, nothing is placed until you approve
              it. Edits have a size limit, and the plugin refuses one the server has no memory for.</p>
          </details>
          <details>
            <summary>Do I have to open a port or change my firewall?</summary>
            <p>No. The plugin opens no port. It makes one outgoing connection to the panel, so it works behind any
              host, including shared hosting.</p>
          </details>
          <details>
            <summary>What does the free plan include?</summary>
            <p>{free.limits.servers === 1 ? 'One server' : `${free.limits.servers} servers`}, {free.limits.members} members,
              unlimited builds and {free.limits.historyDays} days of history and undo. Pro is ${pro.price} a month.{' '}
              <a href="/pricing">Compare the plans</a>.</p>
          </details>
          <details>
            <summary>Which AI models can I use?</summary>
            <p>Any assistant that can use MCP servers. In the browser chat: the Claude models through Anthropic, or
              any model on OpenRouter that can call tools. The building advice is tuned on Claude, so it builds best.</p>
          </details>
        </div>
      </section>

      <section className="closing card">
        <h2>Your next build is one sentence away.</h2>
        <p>Start free on one server. {PRO_INCLUDES_STUDIO
          ? <>For a limited time, Pro at ${pro.price} a month includes everything.</>
          : <>Pro is ${pro.price} a month when you need more.</>}</p>
        <div className="cta">
          {start}
          <a className="button ghost" href="/pricing">See pricing</a>
        </div>
      </section>

      <LandingFooter />
    </div>
  );
}
