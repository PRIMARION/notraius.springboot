-- ── config.types — one type catalogue, in the schema named config ───────────
-- The type registry is configuration, not marketplace reference data, so it
-- lives in `config` while the code that reads it stays under the reference
-- package. Until now notarius kept its catalogue in `notraius.types`; the
-- marketplace's reference.specs then invented `spec_group`, a free code
-- pretending to be a hierarchy. Both are the same truth, so the table moves
-- once and every dependent foreign key moves with it:
--
--   • notraius.types           → config.types (FKs, indexes and the V35
--                                tsvector trigger follow the table)
--   • impression.reaction_types.type_id → already FKs the moved table, so it
--                                now references config.types
--   • reference.specs          → loses spec_group and gains type_id → config.types
--
-- Rows remain admin-seeded: reaction kinds under REACTIONS, spec groupings
-- under SPEC, and anything else the host declares — never a Java list.
CREATE SCHEMA IF NOT EXISTS config;

ALTER TABLE notraius.types SET SCHEMA config;

-- reference.specs: the ATTRIBUTE/CHARACTERISTIC fork stays a varchar because
-- the pricing engine branches on it; the hierarchy is a config.types row.
-- A spec without a type has no selector home, so the column is required.
ALTER TABLE reference.specs DROP CONSTRAINT IF EXISTS uk_specs_group_label;
DROP INDEX IF EXISTS reference.idx_specs_group;
ALTER TABLE reference.specs DROP COLUMN IF EXISTS spec_group;
ALTER TABLE reference.specs ADD COLUMN type_id uuid;
ALTER TABLE reference.specs ALTER COLUMN type_id SET NOT NULL;
ALTER TABLE reference.specs
    ADD CONSTRAINT fk_specs_type FOREIGN KEY (type_id) REFERENCES config.types (id);
ALTER TABLE reference.specs
    ADD CONSTRAINT uk_specs_type_label UNIQUE (type_id, label);
CREATE INDEX idx_specs_type ON reference.specs (type_id);
