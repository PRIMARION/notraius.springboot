package com.notraius.common.integration;

import com.notraius.config.properties.InterServiceProperties;
import com.notraius.config.properties.NotraiusProperties;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.constants.Endpoint;
import org.primarion.module.constants.EndpointRegistry;
import org.primarion.module.utils.security.users.ServiceAuthHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a currency through pay's generic lookup surface.
 * <p>
 * Money lives in pay; notraius never mirrors the currency catalogue, it asks for
 * the id of the ISO code the resolved state implies. The answer is what the
 * property stores in {@code currency_id} — an opaque reference owned by pay,
 * exactly as {@code location_id} is owned by logistics.
 */
@Component
@Slf4j
public class PayCurrencyClient {

    private static final String FIND_CURRENCY = "pay.payment.findCurrency";

    private final RestClient restClient;
    private final EndpointRegistry endpoints;
    private final ServiceAuthHeaders serviceAuthHeaders;
    private final NotraiusProperties properties;

    public PayCurrencyClient(NotraiusProperties properties,
                             InterServiceProperties interServiceProperties,
                             ServiceAuthHeaders serviceAuthHeaders,
                             EndpointRegistry endpoints,
                             RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.serviceAuthHeaders = serviceAuthHeaders;
        this.endpoints = endpoints;
        InterServiceProperties.Timeout timeout = interServiceProperties.getPay();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout.getConnectTimeoutMs());
        factory.setReadTimeout(timeout.getReadTimeoutMs());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.getPayBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** The pay currency id for an ISO 4217 code, or empty when pay does not list it. */
    public Optional<UUID> findCurrencyIdByCode(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return Optional.empty();
        }
        try {
            LookupRow row = restClient.post()
                    .uri(path(FIND_CURRENCY))
                    .headers(h -> h.setAll(serviceAuthHeaders.forTarget(properties.getPlatformTargetPay())))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new LookupRequest("currencies", "findByCode",
                            List.of(new LookupParam("STRING", currencyCode))))
                    .retrieve()
                    .body(LookupRow.class);
            return row != null && row.id() != null ? Optional.of(row.id()) : Optional.empty();
        } catch (Exception e) {
            log.warn("Currency lookup failed for code={}: {}", currencyCode, e.getMessage());
            return Optional.empty();
        }
    }

    private String path(String key) {
        return endpoints.endpoint(key)
                .map(Endpoint::getEndpoint)
                .orElseThrow(() -> new IllegalStateException("Missing endpoint for key '" + key
                        + "' in this service's endpoints.yaml"));
    }

    private record LookupRequest(String tableName, String methodName, List<LookupParam> params) {
    }

    private record LookupParam(String type, Object value) {
    }

    private record LookupRow(UUID id, String tableName, java.util.Map<String, Object> data) {
    }
}
