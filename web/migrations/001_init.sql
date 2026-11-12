-- One row per Auth0 account, made on first sign-in.
CREATE TABLE users (
  id         INT AUTO_INCREMENT PRIMARY KEY,
  sub        VARCHAR(191) NOT NULL UNIQUE,
  email      VARCHAR(320),
  name       VARCHAR(191),
  picture    VARCHAR(1024),
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A Minecraft server a user made. Its plugin connects to /relay with the token whose hash is stored here.
CREATE TABLE servers (
  id           VARCHAR(16) PRIMARY KEY,
  name         VARCHAR(64) NOT NULL,
  owner_id     INT NOT NULL,
  token_hash   CHAR(64) NOT NULL UNIQUE,
  info         JSON,
  last_seen_at DATETIME,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT servers_owner FOREIGN KEY (owner_id) REFERENCES users (id)
);

CREATE TABLE memberships (
  server_id  VARCHAR(16) NOT NULL,
  user_id    INT NOT NULL,
  role       ENUM('owner', 'admin', 'builder', 'viewer') NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (server_id, user_id),
  KEY memberships_user (user_id),
  CONSTRAINT memberships_server FOREIGN KEY (server_id) REFERENCES servers (id) ON DELETE CASCADE,
  CONSTRAINT memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- A single-use invite link. Only the hash of its token is stored.
CREATE TABLE invites (
  id         VARCHAR(16) PRIMARY KEY,
  server_id  VARCHAR(16) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  role       ENUM('admin', 'builder', 'viewer') NOT NULL,
  created_by INT NOT NULL,
  expires_at DATETIME NOT NULL,
  used_by    INT,
  used_at    DATETIME,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY invites_server (server_id),
  CONSTRAINT invites_server FOREIGN KEY (server_id) REFERENCES servers (id) ON DELETE CASCADE,
  CONSTRAINT invites_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT invites_user FOREIGN KEY (used_by) REFERENCES users (id) ON DELETE SET NULL
);

-- A user's token for Claude Code and scripts; reaches every server where the user is builder or above.
CREATE TABLE client_tokens (
  id           VARCHAR(16) PRIMARY KEY,
  user_id      INT NOT NULL,
  label        VARCHAR(64) NOT NULL,
  token_hash   CHAR(64) NOT NULL UNIQUE,
  last_used_at DATETIME,
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY client_tokens_user (user_id),
  CONSTRAINT client_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
