package com.notraius.modules.lease.lease.agreement.mapper;

import com.notraius.database.entities.lease.lease.records.Lease;
import com.notraius.database.entities.lease.lease.lease.LeaseDeposit;
import com.notraius.database.entities.lease.lease.lease.LeaseDepositSplit;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseDepositResponse;
import com.notraius.modules.lease.lease.agreement.dto.response.LeaseResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/**
 * The lease's read shape.
 * <p>
 * The mapper sums the born split ledger per deposit rather than reading a price
 * column that does not exist (PLAN 6.4): the money's truth is custody, and this
 * is the only figure the read side is allowed to add up.
 */
@Component
public class LeaseMapper {

    public LeaseResponse toResponse(Lease lease, List<LeaseDeposit> deposits, List<LeaseDepositSplit> splits) {
        LeaseResponse response = new LeaseResponse();
        response.setId(lease.getId());
        response.setTenantId(lease.getUserId());
        response.setOwnerId(lease.getOwnerId());
        if (lease.getPricing() != null) {
            response.setPricingId(lease.getPricing().getId());
            if (lease.getPricing().getProperty() != null) {
                // The bridge only: the property's title is /properties' answer.
                response.setPropertyId(lease.getPricing().getProperty().getId());
            }
        }
        response.setStatus(lease.getStatus() != null ? lease.getStatus().getCode() : null);
        response.setVersion(lease.getVersion());
        // The contract's own figures: frozen at birth, never the live pricing's
        // (see LeaseResponse — the bridge answers today's offer, not this one).
        response.setRentAmount(lease.getRentAmount());
        response.setCheckable(lease.isCheckable());
        if (lease.getDuration() != null) {
            response.setPeriodValue(lease.getDuration().getValue());
            response.setPeriodUnit(lease.getDuration().getType() != null
                    ? lease.getDuration().getType().getCode() : null);
        }
        if (lease.getValidity() != null) {
            response.setFromDate(lease.getValidity().getFromDate());
            response.setToDate(lease.getValidity().getToDate());
        }
        response.setCreatedAt(lease.getCreatedAt());
        response.setFundingResolved(!splits.isEmpty());
        response.setMatrixTotal(splits.isEmpty() ? null
                : splits.stream().map(LeaseDepositSplit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));

        Map<UUID, List<LeaseDepositSplit>> byDeposit = new HashMap<>();
        for (LeaseDepositSplit split : splits) {
            // A split recovered from pay during a terminal exit has no local
            // deposit attribution; it still counts toward the matrix's total.
            if (split.getDeposit() != null) {
                byDeposit.computeIfAbsent(split.getDeposit().getId(), id -> new java.util.ArrayList<>()).add(split);
            }
        }
        for (LeaseDeposit deposit : deposits) {
            List<LeaseDepositSplit> owned = byDeposit.getOrDefault(deposit.getId(), List.of());
            LeaseDepositResponse row = new LeaseDepositResponse();
            row.setId(deposit.getId());
            row.setPolicyId(deposit.getPolicy() != null ? deposit.getPolicy().getId() : null);
            row.setPolicyCode(deposit.getPolicy() != null ? deposit.getPolicy().getCode() : null);
            row.setTypeCode(deposit.getType() != null ? deposit.getType().getCode() : null);
            row.setBalance(deposit.getBalance());
            row.setStatus(deposit.getStatus() != null ? deposit.getStatus().getCode() : null);
            row.setSplits(owned.size());
            row.setAmount(owned.stream().map(LeaseDepositSplit::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            response.getDeposits().add(row);
        }
        return response;
    }
}
