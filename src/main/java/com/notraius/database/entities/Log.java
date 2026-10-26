package com.notraius.database.entities;

import com.fasterxml.jackson.databind.JsonNode;
import org.primarion.module.access.activity.database.entities.Activity;
import com.notraius.database.entities.reference.Entities;
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
