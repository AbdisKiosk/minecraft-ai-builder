-- One access token per user and server, for Claude Code and scripts on that server only. The token is
-- HMAC(secret, seed), so the panel can show it again; only the seed and the token's hash are stored. It goes when the
-- membership does. This replaces client_tokens (one token for all of a user's servers), which are no longer accepted.
CREATE TABLE access_tokens (
  user_id      INT NOT NULL,
  server_id    VARCHAR(16) NOT NULL,
  seed         CHAR(22) NOT NULL,
  token_hash   CHAR(64) NOT NULL UNIQUE,
  last_used_at DATETIME,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, server_id),
  KEY access_tokens_server (server_id),
  CONSTRAINT access_tokens_membership FOREIGN KEY (server_id, user_id) REFERENCES memberships (server_id, user_id) ON DELETE CASCADE
);
