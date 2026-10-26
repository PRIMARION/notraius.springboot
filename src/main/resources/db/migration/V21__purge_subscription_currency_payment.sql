-- =====================================================================
-- V21 — purge currency_id and payment_id from subscription.subscriptions
-- (UPGRADE.txt).
--
-- Both were copies of facts the pay-side payment row already owns: the
-- charged currency and the payment that carried the charge. Holding them
-- here duplicated the ledger and invited divergence (a retried debit, a
-- voided payment, a currency the wallet never held). The FX audit trail
-- stays in subscriptions.metadata (rate + usd amount + charged amount),
-- which is what reconciliation actually reads.
-- =====================================================================

ALTER TABLE subscription.subscriptions DROP COLUMN IF EXISTS currency_id;
ALTER TABLE subscription.subscriptions DROP COLUMN IF EXISTS payment_id;
