-- ============================================================================
-- V38 — the condition term belongs to the tenancy, not to the live pricing
-- ============================================================================
-- `Pricing.checkable` is editable in place (`updatePricing`) and says whether a
-- tenancy owes the move-in/move-out condition stage. Four post-birth reads went
-- back to the pricing row to ask it: activation's stage, the repair report, the
-- settlement's move-out gate and `bypassesConditions`. So an owner flipping
-- that flag on a live price changed an EXISTING tenancy's obligations — it
-- gained or lost a whole contractual stage retroactively, with no history of
-- the change on the lease and nothing to prove what was agreed.
--
-- `checkable` is a term of the contract, exactly like `rent_amount`. It is
-- therefore frozen at birth, and the tenancy decides its own stages from its
-- own row. Editing the pricing afterwards changes what is OFFERED from then on,
-- never what an existing tenant owes.
--
-- Backfill: existing rows take the pricing's value as it stands today — the
-- only value recoverable, since the pricing was edited in place and the
-- pre-birth value was never stored. This is stated plainly rather than
-- pretended to be exact: from V38 on, the term is a fact of the lease.
-- ============================================================================

ALTER TABLE lease.leases
    ADD COLUMN checkable boolean DEFAULT true NOT NULL;

UPDATE lease.leases l
   SET checkable = p.checkable
  FROM property.pricings p
 WHERE p.id = l.pricing_id;

-- No default from here on: every birth states the term, and a future INSERT
-- that forgets it fails loudly instead of silently claiming "no condition
-- stage".
ALTER TABLE lease.leases
    ALTER COLUMN checkable DROP DEFAULT;
