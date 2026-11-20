package com.notraius.common.integration.pay;

import com.notraius.config.notraius.properties.InterServiceProperties;
import com.notraius.config.notraius.properties.NotraiusProperties;
import lombok.extern.slf4j.Slf4j;
import org.primarion.module.communication.constants.Endpoint;
import org.primarion.module.communication.constants.EndpointRegistry;
import org.primarion.module.utils.utils.security.security.users.session.ServiceAuthHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a property's location through the logistics platform.
 * <p>
 * notraius never parses or copies an address: the property holds the opaque
 * {@code location_id} and asks logistics what state that location sits in. That
 * one answer is the whole bridge from the external address to the local policy
 * chain (location → state → currency → state-scoped standards).
 * <p>
 * The lookup fails closed in both directions: an unknown location (404) answers
 * empty, and an unreadable answer throws — a transport failure must never look
 * like "no state", because a property without a state is stopped, not defaulted.
 */
@Component
@Slf4j
public class LogisticLocationClient {

    private static final String GET_LOCATION = "logistic.location.get";

    private final RestClient restClient;
    private final EndpointRegistry endpoints;
    private final ServiceAuthHeaders serviceAuthHeaders;
    private final NotraiusProperties properties;

    public LogisticLocationClient(NotraiusProperties properties,
                                  InterServiceProperties interServiceProperties,
                                  ServiceAuthHeaders serviceAuthHeaders,
                                  EndpointRegistry endpoints,
                                  RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.serviceAuthHeaders = serviceAuthHeaders;
        this.endpoints = endpoints;
        InterServiceProperties.Timeout timeout = interServiceProperties.getLogistic();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout.getConnectTimeoutMs());
        factory.setReadTimeout(timeout.getReadTimeoutMs());
        this.restClient = restClientBuilder.clone()
                .baseUrl(properties.getLogisticBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** The state the location sits in, or empty when logistics does not know it. */
    public Optional<UUID> resolveStateId(UUID locationId) {
        if (locationId == null) {
            return Optional.empty();
        }
        try {
            LocationWire wire = restClient.get()
                    .uri(path(GET_LOCATION), locationId)
                    .headers(h -> h.setAll(serviceAuthHeaders.forTarget(NotraiusProperties.PLATFORM_TARGET_LOGISTIC)))
                    .retrieve()
                    .body(LocationWire.class);
            if (wire == null || wire.stateId() == null) {
                log.info("Location {} resolved without a state", locationId);
                return Optional.empty();
            }
            return Optional.of(wire.stateId());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.info("Location {} not found in the logistics platform", locationId);
                return Optional.empty();
            }
            log.warn("Location resolution failed for locationId={}: {}", locationId, e.getMessage());
            throw e;
        }
    }

    private String path(String key) {
        return endpoints.endpoint(key)
                .map(Endpoint::getEndpoint)
                .orElseThrow(() -> new IllegalStateException("Missing endpoint for key '" + key
                        + "' in this service's endpoints.yaml"));
    }

    /** The subset of logistics' location detail this service reads. */
    private record LocationWire(UUID locationId, UUID stateId, String statusCode) {
    }
}
