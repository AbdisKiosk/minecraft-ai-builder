# Web app

`web/` is the hosted, multi-user version of the panel. People sign up, add their Minecraft servers, invite others and
make access tokens for Claude Code. It replaces the self-hosted Java panel in `panel/`; the plugin works with both.

```
Claude Code / SDK ──HTTPS──▶ web app  /s/<server>/mcp, /s/<server>/api/<tool>   (access token aibc_…)
Browser           ──HTTPS──▶ web app  pages, /api/…, /api/events               (Auth0 session)
Paper server      ──wss────▶ web app  /relay                                   (server token aibs_…)
                              │
                              ├── MySQL: users, servers, memberships, invites, access tokens, history index
                              └── R2: archived history, Minecraft client jars
```

Next.js alone cannot hold WebSockets, so `server.ts` runs Next.js inside a plain Node HTTP server and takes `/relay`
and `/s/` itself. Next attaches its own upgrade listener for hot reload; `server.ts` must not hand upgrades to it too.

## Run it locally

```bash
cd web
cp .env.example .env.local   # then fill it in
npm install
npm run dev                  # http://localhost:3000
```

The Auth0 application is a Regular Web Application with `http://localhost:3000/auth/callback` as a callback URL and
`http://localhost:3000` as a logout URL. Database migrations in `migrations/` run when the app starts; add a new file
for every change and never edit one that has run. All times are UTC: each connection sets its time zone, whatever the
MySQL server's own is.

To connect a dev server, add a server on the dashboard and put its `relay:` lines in
`plugin/run/<version>/plugins/AIBuilder/config.yml`, with `url: "ws://127.0.0.1:3000"`.

## Deploy

`web/Dockerfile` builds the image (Coolify can build it from the repository with `web` as the base directory). Set the
variables from `.env.example`, with `APP_BASE_URL` the public `https://` address; the plugins then use the matching
`wss://` address. Put TLS in front of it: tokens and the session cookie travel in every request.

## Roles

| Role | Can |
| --- | --- |
| owner | everything, including deleting the server and making a new relay token |
| admin | approve and reject drafts, revert edits, rename, invite and manage builders and viewers |
| builder | build through Claude Code and scripts with an access token, promote regions |
| viewer | see jobs, history and drafts |

Invite links are single use and last seven days. Owners invite admins, builders and viewers; admins invite builders
and viewers. A member can be changed or removed only by someone ranked above them, and anyone but the owner can leave.

## History in R2

When R2 is configured, the app answers a plugin's `hello` with `{"t":"welcome","archive":true}`, and the plugin then
archives every history entry (see `RelayProtocol` for the frames):

1. When a job is recorded, the plugin writes, beside the diff, the 3D view's blocks and the default pictures (isometric,
   from the south-east), made from the job's own snapshot, so this costs no extra world read.
2. It asks for upload URLs, PUTs the files straight to R2 (it never holds R2 keys), then sends the entry's detail.
   The app checks the files are there, stores the detail in MySQL and drops the oldest entries over 5,000 or 2 GB per
   server. The plugin then deletes its copies of the blocks and pictures, and keeps the diff for revert.
3. Entries recorded before the server was connected, or while it was disconnected, are uploaded on the next connection,
   with their diff only.

The dashboard then lists history and shows each entry, its default pictures and its 3D view from the archive, also
while the server is offline. Other picture views still come from the live server. Reverting needs the server online;
if the server no longer keeps the entry, the app sends the archived entry and diff with the request.

Drafts stay on the server: an open draft is working state the plugin's dry runs read from.

Deleting a server deletes its rows and everything under `servers/<id>/` in R2.

## Minecraft client jars

The 3D view takes block models and textures from the Minecraft client jar for the server's version. The app downloads
each version from Mojang once, checks it against Mojang's SHA-1 and keeps it in R2 under `minecraft/client/`; it serves
it only to signed-in users. Servers before 1.13 use the 1.13.2 client. See `docs/panel.md` for the licence question about
the 3D viewer's bundled dependencies, which applies here too.
