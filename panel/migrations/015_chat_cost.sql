-- What a chat has cost in credits, in millionths of a dollar; it stays 0 for a chat on the user's own key.
-- chats.provider (013) now also holds 'credits': the chat runs on the panel's key and the user's credits pay.
ALTER TABLE chats ADD COLUMN cost_micros BIGINT NOT NULL DEFAULT 0 AFTER output_tokens;
