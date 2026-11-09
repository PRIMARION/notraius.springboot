-- ============================================================================
-- V39 — the cadence is a term of the tenancy, not a live read of the pricing
-- ============================================================================
-- `Pricing.duration` is editable in place (`updatePricing` repoints it whenever
-- no count-valued term is in the way), and the calendar derived every
-- installment period from `lease.getPricing().getDuration()` — at the moment the
-- schedule is generated, which is long after the lease was signed. So an owner
-- changing a price from monthly to yearly would silently change how a running
-- tenancy is billed, while the rent itself stayed frozen. The same read priced
-- "unearned rent" in the settlement.
--
-- `duration_id` is therefore frozen at birth, exactly like `rent_amount` (V37)
-- and `checkable` (V38): the tenancy bills on the cadence it was signed at, and
-- editing the offer changes what is offered next, never what is already owed.
--
-- Backfill: existing rows take the pricing's current duration — the only value
-- their birth is recoverable as, since the pointer was edited in place. Stated
-- plainly rather than pretended exact; from V39 on the term is a fact of the
-- lease.
-- ============================================================================

ALTER TABLE lease.leases
    ADD COLUMN duration_id uuid;

UPDATE lease.leases l
   SET duration_id = p.duration_id
  FROM property.pricings p
 WHERE p.id = l.pricing_id;

ALTER TABLE lease.leases
    ALTER COLUMN duration_id SET NOT NULL;

ALTER TABLE lease.leases
    ADD CONSTRAINT fk_leases_on_duration FOREIGN KEY (duration_id) REFERENCES notraius.durations (id);

CREATE INDEX idx_leases_duration_id ON lease.leases USING btree (duration_id);
