-- The provider a chat's requests go to, fixed by its first message. The chats made before this used Anthropic's API.
ALTER TABLE chats ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'anthropic' AFTER title;
