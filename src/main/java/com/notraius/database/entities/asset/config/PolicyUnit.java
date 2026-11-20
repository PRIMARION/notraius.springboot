package com.notraius.database.entities.asset.config;

import java.util.Locale;

/**
 * How a policy's value is READ (PLAN.md 3.2) — a different question from what
 * it bounds:
 * <ul>
 *   <li><b>UNITS</b> — the value counts units of the bound pricing's duration
 *       (two months of advance on a MONTH+1 pricing).</li>
 *   <li><b>AMOUNT</b> — the value is a raw amount of money in the property's
 *       currency (a 150 000 security deposit).</li>
 * </ul>
 * The lease and deposit machinery live entirely on this column, which is why it
 * is fixed at birth: changing a policy from UNITS to AMOUNT would reinterpret
 * every stored value as the other kind of thing.
 */
public enum PolicyUnit {
    UNITS,
    AMOUNT;

    /** Case-insensitive lookup; null when the token names no unit. */
    public static PolicyUnit from(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
