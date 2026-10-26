package com.notraius.modules.core.common.constants;

/**
 * Notraius activity vocabulary — families addressable by the shared event
 * engine and webhook dispatcher (the part before the first dot). The domain
 * grows here as the notraius modules materialize; today the class is the
 * registration anchor referenced by {@code app.activities.classes} in
 * application.yaml.
 */
public final class NotraiusActivities {
    private NotraiusActivities() {}

    // ── Activity families (grows with the domain) ─────────────────
    // None yet — notraius is born without a domain; add a family per
    // module (e.g. ESCROW for a notraius escrow chain) as they arrive.
}
