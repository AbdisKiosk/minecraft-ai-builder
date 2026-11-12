-- A user's asset library: pieces (a window, a pillar, a tree) saved from a server's world. An asset belongs to the
-- account, not to the server it came from. The blocks are in R2 under users/<user>/assets/<id>/: region.bin (the
-- draft export frame's data, which the draft import frame takes back) and blocks.json.gz (the 3D view).
CREATE TABLE assets (
  id          VARCHAR(16) PRIMARY KEY,
  user_id     INT NOT NULL,
  -- Same rule as the plugin's saved blueprints (a-z, 0-9, _ and -), so Claude can name it in a paste later.
  name        VARCHAR(40) NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  -- A JSON array of short lowercase strings.
  tags        JSON NOT NULL,
  size_x      INT NOT NULL,
  size_y      INT NOT NULL,
  size_z      INT NOT NULL,
  -- Blocks that are not air.
  block_count INT NOT NULL,
  -- The Minecraft version of the server it was saved on, as that server reported it.
  mc_version  VARCHAR(64),
  -- Both files in R2 together, for the per-user quota.
  bytes       BIGINT NOT NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY assets_name (user_id, name),
  CONSTRAINT assets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
