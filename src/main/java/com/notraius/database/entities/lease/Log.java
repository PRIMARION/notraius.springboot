package com.notraius.database.entities.lease;

import com.fasterxml.jackson.databind.JsonNode;
import org.primarion.module.app.access.consent.database.Activity;
import com.notraius.database.entities.lease.reference.amenity.Entities;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class Log {
    private UUID id;
    private Instant createdAt;
    private Instant updatedAt;
    private Entities entity;
    private UUID referenceId;
    private UUID deviceId;
    private JsonNode oldData;
    private JsonNode newData;
    private String actor;
    private UUID createdBy;
    private Activity activity;
    private String operation;
    private UUID sessionId;
}
