package com.notraius.modules.lease.asset.property.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Anchor the property: the location is an opaque reference into the logistics
 * platform, and the backend derives the state — and through it the currency —
 * from that reference. The client never sends a state or a currency; there is
 * no field for either, because neither is the user's to choose.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PropertyLocationRequest {

    @NotNull
    private UUID locationId;
}
