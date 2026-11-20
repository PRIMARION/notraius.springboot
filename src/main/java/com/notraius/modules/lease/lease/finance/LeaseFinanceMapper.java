package com.notraius.modules.lease.lease.finance;

import com.notraius.database.entities.lease.lease.records.LeaseBreakdown;
import com.notraius.database.entities.lease.lease.lease.LeaseTopUp;
import com.notraius.modules.lease.lease.finance.dto.response.LeaseInstallmentResponse;
import com.notraius.modules.lease.lease.finance.dto.response.LeaseTopUpResponse;
import org.springframework.stereotype.Component;

/**
 * The finance sub-module's read shapes. Nothing here re-derives money: the
 * breakdown's amount is the frozen figure the calendar wrote, and the top-up's
 * own row is the funding event's truth.
 */
@Component
public class LeaseFinanceMapper {

    public LeaseInstallmentResponse toResponse(LeaseBreakdown breakdown) {
        LeaseInstallmentResponse response = new LeaseInstallmentResponse();
        response.setId(breakdown.getId());
        response.setLeaseId(breakdown.getLease() != null ? breakdown.getLease().getId() : null);
        response.setTitle(breakdown.getTitle());
        response.setType(breakdown.getType() != null ? breakdown.getType().getCode() : null);
        response.setStatus(breakdown.getStatus() != null ? breakdown.getStatus().getCode() : null);
        response.setAmount(breakdown.getAmount());
        response.setUserId(breakdown.getUserId());
        response.setDeadline(breakdown.getDeadline());
        response.setPeriodIndex(breakdown.getPeriodIndex());
        response.setPeriodStart(breakdown.getPeriodStart());
        response.setPeriodEnd(breakdown.getPeriodEnd());
        response.setEscrowPaymentId(breakdown.getEscrowPaymentId());
        response.setEscrowSplitId(breakdown.getEscrowSplitId());
        response.setSettledAt(breakdown.getSettledAt());
        response.setAssessmentFiled(breakdown.isAssessmentFiled());
        response.setVersion(breakdown.getVersion());
        return response;
    }

    public LeaseTopUpResponse toResponse(LeaseTopUp topUp) {
        LeaseTopUpResponse response = new LeaseTopUpResponse();
        response.setId(topUp.getId());
        response.setLeaseId(topUp.getLease() != null ? topUp.getLease().getId() : null);
        response.setDepositId(topUp.getDeposit() != null ? topUp.getDeposit().getId() : null);
        response.setAmount(topUp.getAmount());
        response.setUnits(topUp.getUnits() != null ? topUp.getUnits() : 0);
        response.setPaymentId(topUp.getPaymentId());
        response.setStatus(topUp.getStatus() != null ? topUp.getStatus().getCode() : null);
        response.setVersion(topUp.getVersion());
        response.setCreatedAt(topUp.getCreatedAt());
        return response;
    }
}
