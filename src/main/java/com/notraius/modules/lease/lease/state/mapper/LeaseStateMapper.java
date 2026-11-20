package com.notraius.modules.lease.lease.state.mapper;

import com.notraius.database.entities.lease.lease.lease.LeaseRepair;
import com.notraius.database.entities.lease.lease.records.LeaseSettlement;
import com.notraius.modules.lease.lease.state.dto.response.LeaseRepairResponse;
import com.notraius.modules.lease.lease.state.dto.response.LeaseSettlementResponse;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * The end-of-life read shapes. Money totals are filled by the service, because
 * they are sums over rows the mapper must not fetch on its own; everything else
 * is the row's own word.
 */
@Component
public class LeaseStateMapper {

    public LeaseSettlementResponse toResponse(LeaseSettlement settlement) {
        LeaseSettlementResponse response = new LeaseSettlementResponse();
        response.setId(settlement.getId());
        response.setLeaseId(settlement.getLease() != null ? settlement.getLease().getId() : null);
        response.setStatus(settlement.getStatus() != null ? settlement.getStatus().getCode() : null);
        response.setDeadline(settlement.getDeadline());
        response.setMoveOutDeadline(settlement.getMoveOutDeadline());
        response.setConfirmedAt(settlement.getConfirmedAt());
        response.setConfirmedBy(settlement.getConfirmedBy());
        response.setVersion(settlement.getVersion());
        response.setCreatedAt(settlement.getCreatedAt());
        return response;
    }

    public LeaseRepairResponse toResponse(LeaseRepair repair, java.util.UUID chargeBreakdownId) {
        LeaseRepairResponse response = new LeaseRepairResponse();
        response.setId(repair.getId());
        response.setLeaseId(repair.getLease() != null ? repair.getLease().getId() : null);
        response.setConditionId(repair.getCondition() != null ? repair.getCondition().getId() : null);
        response.setTitle(repair.getTitle());
        response.setDescription(repair.getDescription());
        response.setStatus(repair.getStatus() != null ? repair.getStatus().getCode() : null);
        // The charge is a bridge: amount and payer live on that installment row.
        response.setChargeBreakdownId(chargeBreakdownId);
        response.setVersion(repair.getVersion());
        response.setCreatedAt(repair.getCreatedAt());
        return response;
    }
}
