package com.notraius.modules.config.rules.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A rule as the API speaks it — the current version of the truth, with the
 * version number a client needs when it asks for an older one from the log
 * chain.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleResponse {

    private UUID id;

    private String title;

    private String description;

    /** PUBLIC or PRIVATE. */
    private String visibility;

    /** Birth is 1; every accepted edit increments it. */
    private Integer version;

    /** ACTIVE, DISABLED or RETIRED. */
    private String statusCode;

    private UUID creatorId;

    private Instant createdAt;

    private Instant updatedAt;
}
