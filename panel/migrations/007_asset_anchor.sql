-- Where an asset attaches and which way it faces, so Claude can paste it with "at" + "facing" instead of working out
-- the lowest corner. anchor_x/y/z: the attachment cell, zero-based from the asset's lowest corner. facing: north,
-- south, east, west, up or down, the direction the asset faces away from what it attaches to. Both are NULL for
-- every asset saved before this and for uploaded .schem files: an invented anchor would place old pieces in the
-- wrong spot, so NULL keeps the lowest-corner placement. Each may be set without the other.
ALTER TABLE assets
  ADD COLUMN anchor_x INT NULL,
  ADD COLUMN anchor_y INT NULL,
  ADD COLUMN anchor_z INT NULL,
  ADD COLUMN facing VARCHAR(8) NULL;
