package com.notraius.database.entities.config;

import java.util.Locale;

/**
 * How bright a rule's light is, chosen by its creator at birth (PLAN.md 3.1).
 * <ul>
 *   <li><b>PUBLIC</b> — every authenticated user may read it and select it for
 *       a property.</li>
 *   <li><b>PRIVATE</b> — its creator alone may read and select it, with two
 *       documented exceptions: a property that already holds the selection
 *       shows the rule through its own listing, and staff roles read it for
 *       moderation (read authority, never adoption).</li>
 * </ul>
 * There is no third value: no unlisted, no share-with-named-users. PRIVATE →
 * PUBLIC is always the creator's right; PUBLIC → PRIVATE is refused while
 * someone else's property relies on the rule, because the flip would orphan a
 * selection its owner can no longer see.
 */
public enum RuleVisibility {
    PUBLIC,
    PRIVATE;

    /** Case-insensitive lookup; null when the token names no visibility. */
    public static RuleVisibility from(String token) {
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
