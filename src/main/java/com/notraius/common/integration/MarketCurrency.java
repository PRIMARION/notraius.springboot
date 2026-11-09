package com.notraius.common.integration;

import java.util.Locale;
import java.util.Optional;

/**
 * The currency of a market, as an ISO 4217 code keyed by the country the shared
 * geo lookup reports for a property's state.
 * <p>
 * <b>Why this exists.</b> The operator's ruling is that a property's currency is
 * never chosen, typed or edited: the user decides the property's <em>location</em>,
 * the backend resolves the state, and the state's country decides the currency.
 * The geo lookup knows countries but deliberately carries no currency (money
 * lives in pay), so this is the one translation between the two vocabularies —
 * a fixed vocabulary expressed as an enum, which is how the estate spells fixed
 * vocabularies.
 * <p>
 * The two markets named in PLAN.md 4.3 (Benin versus Nigeria) are the launch
 * markets. Adding a market is one line here plus the currency existing in pay:
 * the resolution fail-closes on any country not listed, so an unmapped market
 * refuses the anchor loudly instead of guessing a unit of money.
 */
public enum MarketCurrency {

    BENIN("BJ", "XOF"),
    NIGERIA("NG", "NGN");

    private final String countryAlpha2;
    private final String currencyCode;

    MarketCurrency(String countryAlpha2, String currencyCode) {
        this.countryAlpha2 = countryAlpha2;
        this.currencyCode = currencyCode;
    }

    public String countryAlpha2() {
        return countryAlpha2;
    }

    public String currencyCode() {
        return currencyCode;
    }

    /** The ISO 4217 code for a country, or empty when the market is not mapped. */
    public static Optional<String> codeFor(String countryAlpha2) {
        if (countryAlpha2 == null) {
            return Optional.empty();
        }
        String normalised = countryAlpha2.trim().toUpperCase(Locale.ROOT);
        for (MarketCurrency market : values()) {
            if (market.countryAlpha2.equals(normalised)) {
                return Optional.of(market.currencyCode);
            }
        }
        return Optional.empty();
    }
}
