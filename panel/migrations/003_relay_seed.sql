-- The relay token is derived from this seed and the app's secret, so the panel can hand out a server's plugin jar
-- (with its token inside) again without storing the token. NULL for servers whose token was made before.
ALTER TABLE servers ADD COLUMN relay_seed CHAR(22) NULL;
