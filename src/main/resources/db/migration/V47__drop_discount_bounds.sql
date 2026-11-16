-- The discount template loses its floor and cap (§19).
--
-- reference.discounts' min_amount / max_amount were two columns with two
-- irreconcilable meanings: the grant side read them as bounds on the GRANTED
-- VALUE, the evaluation side as bounds on the qualifying LINE AMOUNT — so a
-- "5% off" grant on a min=50 template was impossible to author and meaningless
-- if it existed. Both readings are purged: an offer rides its variant, the
-- granted value is the offer's only figure, and MoneyMath's clamp law
-- (discount ≤ amount) is the one bound every line already carries. A template
-- that needs a minimum-spend mechanic gets a column of its own when the plan
-- asks for one — not a borrowed pair of amounts.

ALTER TABLE reference.discounts
    DROP COLUMN IF EXISTS min_amount,
    DROP COLUMN IF EXISTS max_amount;
