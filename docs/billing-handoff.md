# Billing handoff

Where the Stripe work stands on the `pricing-page` branch, for whoever picks it up next. Written 2026-11-13.

## Where the branch is

`pricing-page` is pushed and holds these commits that are not on `main`:

- `43ab536` Add plans and enforce their limits in the panel
- `ce8b71f` Sell Pro through Stripe Checkout, with a Billing page
- this handoff, and after it the webhook's unit tests and the billing docs

Everything before them (the pricing page, "AI" instead of Claude, Connect your AI) was merged as pull request 11.
`panel/` typechecks and its 133 tests pass. The plugin and the SDK are untouched.

## What is built

**Plans** (`panel/lib/plans.ts`, pure, tested in `test/plans.test.ts`). Free, Pro ($10) and Studio ($29) with their
limits, the refusal messages, and `PRO_INCLUDES_STUDIO`. While that constant is true, Pro allows what Studio does: this
is the temporary discount, and turning it off is the one switch that ends it. The pricing page reads its numbers from
this file.

**An account's plan** (`migrations/010_billing.sql`): `users.plan`, `stripe_customer`, `stripe_subscription`,
`plan_status`, `plan_renews_at`, `plan_cancels`. A server has its owner's plan.

**Enforcement** (`lib/server/billing.ts`: `limitsFor(user)`, `serverLimits(server)`). Refusals answer 402.

| Limit | Where |
|---|---|
| Servers an account owns | `POST /api/servers` |
| Members of a server | making an invite, and again when it is accepted (`lib/server/invites.ts`) |
| History window | the list leaves older entries out and counts them in `closed`; opening or reverting an archived older entry is refused (`app/api/servers/[id]/[...path]/route.ts`, `archive.list`) |
| Asset library | saving, uploading and sharing, from the panel, from the AI and in game (`Deps.allowSave`); existing assets stay usable |
| REST API | `/s/<server>/api/*` in `plugin-proxy.ts`; MCP is in every plan |
| Promote | `POST /api/promote` between two servers; within one server it is allowed |

Limits apply only when `STRIPE_SECRET_KEY` is set. A panel without it sells nothing and allows everything, so a
self-hosted panel and today's production are unaffected until the key is added.

**Stripe** (`lib/server/billing.ts`, routes in `app/api/billing/`): `checkout` (a plain link target that redirects to
Stripe Checkout), `return` (Checkout's success URL; syncs the plan straight away), `portal` (Stripe's Customer
Portal), `webhook`, and `GET /api/billing` for the dashboard. Every path calls `syncCustomer`, which asks Stripe for
the customer's subscriptions and stores what they grant, so events may arrive late, twice or out of order.

**UI**: a Billing page in the dashboard (`components/BillingPage.tsx`, `#/billing`, nav link shown only when billing
is on), a note under a server's history when older edits are closed, and the pricing page's Pro button going to
`/api/billing/checkout`.

## What was checked, and how

In a browser against the dev database and Stripe's sandbox:

- A Free account is refused a second server (402) and the REST API (402); MCP is not refused.
- Upgrade goes to Stripe Checkout, a payment with test card 4242 4242 4242 4242 succeeds, and the browser comes back to
  a Billing page showing Pro with its renewal date.
- After that the REST API is no longer refused, and Manage billing opens the Customer Portal with the subscription.

## What is not done

1. ~~The webhook has never run.~~ Done on 2026-11-13 against the sandbox, with `stripe listen` forwarding to a local
   panel: a subscription made, set to cancel at the period end, resumed and ended through Stripe's API each delivered
   an event that the panel answered 200, and the account's row followed (Pro and active, `plan_cancels` 1, back to 0,
   then Free and `canceled`). An unsigned delivery is answered 400. The logic is also unit tested
   (`test/billing.test.ts`, `planState` in `test/plans.test.ts`). Not seen yet: the Billing page's "Cancelled: the plan
   ends on …" line in a browser. `stripe listen --api-key <test key>` needs no `stripe login`.
2. **Review mode and WorldGuard protection are not enforced.** The pricing page lists them under Pro and Studio, but
   both are settings in the plugin's own `config.yml`, which the panel does not control. Enforcing them means the
   panel telling the plugin its plan over the relay (`RelayProtocol` and `hub.ts` together) and the plugin refusing.
   Until then the pricing page promises a limit that does not exist.
3. ~~Not exercised in a browser.~~ Done on 2026-11-13 with a signed-in session against the real routes, on a local
   database seeded with a full server, a second owner and backdated history:
   - Free: a second server, an invite on a full server, saving an asset, promote between two servers, opening and
     reverting a history entry older than 7 days, and the REST API each answer 402 with the reason; accepting an
     invite into a server that filled up answers 410 "This server is full"; the history list leaves the old entries
     out and counts them in `closed`. MCP and promote within one server are not refused.
   - After subscribing (webhook), every one of those is allowed, and the Billing page shows Pro with its renewal date.
   - Cancelling in the Customer Portal makes the Billing page say "Cancelled: the plan ends on …".
   - Still unseen: the note under a server's history, which needs a connected plugin, and a member limit hit by a
     real second account (the second member was a seeded row). The history check also skips entries the plugin has
     not archived yet.
   - Found and fixed: a member who is not the owner was told to "Upgrade on the Billing page" for the REST API and
     for promote, though only the owner's plan counts. They are now pointed to the owner, and the note under a
     server's history links to Billing for the owner only.
4. ~~Docs~~ Done: "Billing" in `docs/panel.md`, and a line in `CLAUDE.md`.
5. **Studio cannot be bought.** `STRIPE_PRICE_STUDIO` is read, but checkout only sells Pro.
6. **Going live** is the owner's: live keys in production, the webhook endpoint registered in Stripe for
   `checkout.session.completed` and `customer.subscription.created/updated/deleted`, terms, refund and privacy pages,
   and VAT (the owner is the seller; business country and VAT registration are still unanswered). Checkout showed the
   price converted to DKK by Stripe; whether $10 includes VAT is undecided.

## Decisions already made by the owner

- The account that owns a server pays; members use the owner's plan.
- Only Pro is sold now, with everything unlocked while the discount runs.
- On a downgrade or failed payment nothing is deleted: what is over the limit stays and only more is refused.
  `past_due` keeps the plan while Stripe retries the card.
- Existing accounts over the Free limits keep what they have.
- Wording: "AI", not Claude; "temporary discount", not early tester pricing.

## The dev setup on the machine the work started on

A second machine that continued the work has its own: `panel/.env.development.local` with the Stripe test key, the
webhook secret and a `DATABASE_URL` for MySQL 8.4 in Docker (`aibuilder-dev-mysql`, port 3307 on localhost only,
database `aibuilder_dev`), holding made-up accounts and servers for the tests. Its dev Minecraft server
(`plugin/core/run/1.21.11/`) connects to the local panel as server "Dev A", and the project's local `aibuilder` MCP
entry points at `http://localhost:3000/s/<that server>/mcp`.

- **Never start the panel with `panel/.env.local` alone: its `DATABASE_URL` is production**, and the panel runs
  migrations on startup. `panel/.env.development.local` (git-ignored, read first by `npm run dev`) overrides it with a
  local MySQL, and holds `STRIPE_SECRET_KEY` (a test key) and `STRIPE_PRICE_PRO` (a product id, `prod_…`).
- The owner set `R2_BUCKET` in that file to a value of their own; object storage was not tested with it.
- MySQL 8.4 runs in Docker as `aibuilder-dev-mysql` (database `aibuilder_dev`, port 3306 on localhost only, data in a
  volume). Docker is the Homebrew CLI on Colima; after a reboot run `colima start`.
- The dev database has one account, one server ("Dev Test") and an access token for it. That account has a real
  sandbox subscription to Pro, so it is on Pro. To test Free again, cancel it in the portal and run the webhook, or
  set `users.plan` to `free` by hand.
- The panel's sign-in uses the real Auth0 tenant. The owner is signed in to `localhost:3000` in Chrome, not in the
  app's browser pane.
- `panel/run/`, `builds/clubhouse2/`, `docs/setup.md` and `docs/3d-viewer-brief.md` are the owner's untracked files:
  leave them out of commits (`git add panel` picks up `panel/run/`).

## Suggested order

Open a pull request. Plugin-side enforcement (2) is a
change of its own and touches the relay protocol.
