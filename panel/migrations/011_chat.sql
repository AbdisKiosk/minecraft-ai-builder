-- Chat on the panel (lib/server/chat/): a user's own Anthropic API key, and their conversations with Claude about one
-- server. Three tables in one file, so each is IF NOT EXISTS: MySQL commits DDL at once, and a run that failed between
-- them must be able to run again.

-- The key is sealed with AES-256-GCM under a key derived from KEY_SECRET (lib/server/secretBox.ts), so a leaked
-- database gives no working key. hint is its last four characters, the only part the panel shows again.
CREATE TABLE IF NOT EXISTS api_keys (
  user_id    INT NOT NULL PRIMARY KEY,
  sealed     TEXT NOT NULL,
  hint       VARCHAR(8) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT api_keys_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- One conversation: a user's, about one server. system_prompt is fixed by the first turn, so later turns reuse
-- Anthropic's prompt cache. error is why the last turn stopped early, shown until the next one starts.
CREATE TABLE IF NOT EXISTS chats (
  id            VARCHAR(16) NOT NULL PRIMARY KEY,
  server_id     VARCHAR(16) NOT NULL,
  user_id       INT NOT NULL,
  title         VARCHAR(120) NOT NULL,
  model         VARCHAR(64) NOT NULL,
  system_prompt MEDIUMTEXT,
  input_tokens  BIGINT NOT NULL DEFAULT 0,
  output_tokens BIGINT NOT NULL DEFAULT 0,
  error         TEXT,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY chats_owner (server_id, user_id, updated_at),
  CONSTRAINT chats_server FOREIGN KEY (server_id) REFERENCES servers (id) ON DELETE CASCADE,
  CONSTRAINT chats_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- The messages as the Messages API takes them: content is the JSON of a string or of content blocks, kept exactly as
-- Claude returned it (thinking blocks included), because a later turn must send the history back unchanged.
CREATE TABLE IF NOT EXISTS chat_messages (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  chat_id    VARCHAR(16) NOT NULL,
  role       ENUM('user', 'assistant') NOT NULL,
  content    LONGTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY chat_messages_chat (chat_id, id),
  CONSTRAINT chat_messages_chat FOREIGN KEY (chat_id) REFERENCES chats (id) ON DELETE CASCADE
);
