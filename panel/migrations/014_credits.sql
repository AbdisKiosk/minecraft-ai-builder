-- Credits (lib/credits.ts, lib/server/credits.ts): a prepaid balance that pays for chats on the panel's own Anthropic
-- key. This is the ledger, and an account's balance is the sum of its rows; nothing else stores it. micros is millionths
-- of a dollar added to the balance: positive for a top-up, negative for usage, a refund or a disputed payment.
--
-- ref makes an entry from Stripe arrive once however often its webhook does: the payment intent of a top-up
-- ('pi_...'), 'refund:<charge>' and 'dispute:<dispute>'. A usage row is one turn of a chat: it is made when the turn
-- starts and grows with every request of the turn, so a panel that stops mid-turn has still charged what was used.
-- chat_id is not a foreign key: the entry stays when the chat is deleted.
CREATE TABLE IF NOT EXISTS credit_ledger (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       INT NOT NULL,
  kind          ENUM('topup', 'usage', 'refund', 'dispute', 'grant') NOT NULL,
  micros        BIGINT NOT NULL,
  ref           VARCHAR(96) UNIQUE,
  chat_id       VARCHAR(16),
  model         VARCHAR(64),
  input_tokens  BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY credit_ledger_owner (user_id, id),
  CONSTRAINT credit_ledger_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
