-- History entries archived from the servers' plugins. The blocks themselves are in R2 under
-- servers/<server>/history/<entry>/: diff.gz (for revert), blocks.json.gz (3D view), render.json.gz (default pictures).
CREATE TABLE history (
  server_id  VARCHAR(16) NOT NULL,
  entry_id   VARCHAR(32) NOT NULL,
  time_ms    BIGINT NOT NULL,
  -- The entry with its stats, block types and first changes, as the plugin's history "get" answers.
  detail     JSON NOT NULL,
  -- The files in R2, comma separated: diff, blocks, render.
  files      VARCHAR(64) NOT NULL DEFAULT '',
  bytes      BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (server_id, entry_id),
  KEY history_time (server_id, time_ms),
  CONSTRAINT history_server FOREIGN KEY (server_id) REFERENCES servers (id) ON DELETE CASCADE
);
