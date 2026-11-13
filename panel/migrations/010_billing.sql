-- What an account pays for (lib/server/billing.ts). plan is what the account's Stripe subscription grants right now,
-- 'free' without one; plan_status is Stripe's word for that subscription, plan_renews_at the end of the paid period.
ALTER TABLE users
  ADD COLUMN plan ENUM('free', 'pro', 'studio') NOT NULL DEFAULT 'free',
  ADD COLUMN stripe_customer VARCHAR(64) UNIQUE,
  ADD COLUMN stripe_subscription VARCHAR(64),
  ADD COLUMN plan_status VARCHAR(32),
  ADD COLUMN plan_renews_at DATETIME,
  ADD COLUMN plan_cancels TINYINT(1) NOT NULL DEFAULT 0;
