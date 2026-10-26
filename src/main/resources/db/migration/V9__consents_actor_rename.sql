-- ═══════════════════════════════════════════════════════════════════════
-- V9 — consents actor rename (reframe Phase 2).
-- IDENTICAL content across hosts: contract V12 · pay V9 · notraius V9 ·
-- primarion V10 (same porting pattern as the seats V5/V6 delegation file).
--
-- access.consents carried a bare string `entity` + polymorphic
-- `reference_id`, while every other table's `entity_id` means "row in the
-- contract.entities registry" — the same word, two meanings, no
-- referential integrity. The consents pair never pointed at an entity
-- registry row: it names WHO may hold a grant (the grantee). Rename to the
-- event.events vocabulary (uc_events_actor_pair precedent):
--
--   entity       -> actor        grantee TYPE, CHECK IN ('APP','SEAT') kept
--   reference_id -> actor_id     grantee row id
--
-- uk_consents_grant follows its columns automatically; chk_consents_entity
-- is renamed chk_consents_actor (same predicate); idx_consents_entity_ref
-- becomes idx_consents_actor. Pure rename — no data, no semantics change.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE access.consents RENAME COLUMN entity TO actor;
ALTER TABLE access.consents RENAME COLUMN reference_id TO actor_id;
ALTER TABLE access.consents RENAME CONSTRAINT chk_consents_entity TO chk_consents_actor;
ALTER INDEX access.idx_consents_entity_ref RENAME TO idx_consents_actor;
