package com.notraius.modules.asset.property.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A property's occupancy as the market may see it (UPGRADE.md D2 / Table 3):
 * that it is occupied, and until when — <b>dates only</b>.
 * <p>
 * Deliberately absent: the tenant, the lease id, the rent, the deposits, the
 * matrix, any debt or payment id. The full lease stays at {@code GET
 * /leases/{id}}, party-only — publishing a lease id here would turn this
 * projection into a probe handle for that door.
 * <p>
 * {@code statusCode} is the property's own status ({@code LEASED} while a live
 * tenancy stands); {@code leasedFrom}/{@code leasedUntil} are the current
 * tenancy's window and are null when no live lease carries one.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TenancyResponse {

    private UUID propertyId;

    /** The property's status code — the public statement ("LEASED"), not the lease's. */
    private String statusCode;

    private Instant leasedFrom;

    private Instant leasedUntil;
}
