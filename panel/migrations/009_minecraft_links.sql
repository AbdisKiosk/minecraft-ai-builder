-- A Minecraft player linked to a panel account, so `/aibuilder asset save` in game saves into that account's library.
-- The link is global (not per server): a player UUID belongs to at most one account, and every save still needs the
-- builder role on the server it comes from. lib/server/minecraftLinks.ts holds the rules.
CREATE TABLE minecraft_links (
  uuid        CHAR(36) NOT NULL PRIMARY KEY,
  user_id     INT NOT NULL,
  -- The player's name as a server last reported it, shown only on the panel.
  player_name VARCHAR(32),
  linked_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY minecraft_links_user (user_id),
  CONSTRAINT minecraft_links_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- One-time codes a signed-in user makes on the panel and types in game with /aibuilder link. Only the SHA-256 of a
-- code is stored. A code lasts ten minutes and is spent by its first use or by the user's next code.
CREATE TABLE minecraft_link_codes (
  code_hash  CHAR(64) NOT NULL PRIMARY KEY,
  user_id    INT NOT NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  used_at    DATETIME,
  KEY minecraft_link_codes_user (user_id, created_at),
  CONSTRAINT minecraft_link_codes_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
