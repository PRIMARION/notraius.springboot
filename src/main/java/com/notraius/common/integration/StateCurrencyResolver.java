package com.notraius.common.integration;

import lombok.extern.slf4j.Slf4j;
import org.primarion.database.autoconfigure.SharedLookupCacheService;
import org.primarion.module.geo.database.entities.Country;
import org.primarion.module.geo.database.entities.State;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The third link of the anchor chain: state → currency.
 * <p>
 * The state is resolved from the property's location (logistics), the state's
 * country is read from the shared geo lookup, and the country's market currency
 * is resolved to a real pay currency id. Nothing here is user input; the same
 * chain is why policy standards are state-scoped in the first place — different
 * states mean different countries, mean different money.
 * <p>
 * Fail-closed by construction: an unknown state, an unmapped country or a
 * currency pay does not list all answer empty, and the caller refuses the anchor
 * rather than inventing a unit of money.
 */
@Component
@Slf4j
public class StateCurrencyResolver {

    private final SharedLookupCacheService lookups;
    private final PayCurrencyClient payCurrencyClient;

    public StateCurrencyResolver(SharedLookupCacheService lookups, PayCurrencyClient payCurrencyClient) {
        this.lookups = lookups;
        this.payCurrencyClient = payCurrencyClient;
    }

    public Optional<UUID> currencyForState(UUID stateId) {
        if (stateId == null) {
            return Optional.empty();
        }
        Optional<String> countryAlpha2 = lookups.getStateById(stateId)
                .map(State::getCountry)
                .map(Country::getAlpha2);
        if (countryAlpha2.isEmpty()) {
            log.warn("State {} is unknown to the geo lookup; no currency can be derived", stateId);
            return Optional.empty();
        }
        Optional<String> currencyCode = MarketCurrency.codeFor(countryAlpha2.get());
        if (currencyCode.isEmpty()) {
            log.warn("State {} sits in country {} which has no mapped market currency",
                    stateId, countryAlpha2.get());
            return Optional.empty();
        }
        return payCurrencyClient.findCurrencyIdByCode(currencyCode.get());
    }
}
