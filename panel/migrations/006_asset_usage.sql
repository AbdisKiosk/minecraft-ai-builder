-- Usage, rather than renaming/describing, orders the short library list Claude reads.
ALTER TABLE assets ADD COLUMN last_used_at DATETIME NULL;
CREATE INDEX assets_recent ON assets (user_id, last_used_at);
