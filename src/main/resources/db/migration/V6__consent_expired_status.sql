-- EXPIRED is now a stampable CONSENTS status: ConsentRevocationJob stamps it
-- on GRANTED/PENDING grants whose validity window has closed. Liveness stays
-- "GRANTED + in-window" at read time (EXPIRED is never live); the stamp is the
-- persisted mirror. REVOKED is reserved for deliberate revocation (grantor,
-- demotion, account kill).
INSERT INTO notraius.statuses (id, code, description, entity_id, created_at, updated_at)
SELECT gen_random_uuid(), 'EXPIRED',
       'Consent window closed — no longer live', e.id, now(), now()
FROM notraius.entities e
WHERE e.code = 'CONSENTS'
  AND NOT EXISTS (
    SELECT 1 FROM notraius.statuses s
    WHERE s.code = 'EXPIRED' AND s.entity_id = e.id);

UPDATE notraius.statuses
SET description = 'Consent revoked (by grantor, demotion or account kill)'
WHERE code = 'REVOKED'
  AND entity_id IN (SELECT id FROM notraius.entities WHERE code = 'CONSENTS');
