-- An owner shares an asset with a server: every builder there (and above) can list, place and paste it while the
-- owner is still a builder there too. The asset, its files and its quota stay the owner's; only the owner shares,
-- unshares, renames or deletes it. Deleting the asset, the server or the user removes the share.
CREATE TABLE asset_shares (
  asset_id   VARCHAR(16) NOT NULL,
  server_id  VARCHAR(16) NOT NULL,
  shared_by  INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (asset_id, server_id),
  KEY asset_shares_server (server_id),
  CONSTRAINT asset_shares_asset FOREIGN KEY (asset_id) REFERENCES assets (id) ON DELETE CASCADE,
  CONSTRAINT asset_shares_server FOREIGN KEY (server_id) REFERENCES servers (id) ON DELETE CASCADE,
  CONSTRAINT asset_shares_user FOREIGN KEY (shared_by) REFERENCES users (id) ON DELETE CASCADE
);
