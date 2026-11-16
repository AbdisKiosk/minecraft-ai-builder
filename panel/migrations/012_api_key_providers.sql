-- A user can keep one API key per provider for the chat (PROVIDERS in lib/chat.ts): Anthropic, OpenRouter. The keys
-- stored before this were all Anthropic's.
ALTER TABLE api_keys
  ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'anthropic' AFTER user_id,
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (user_id, provider);
