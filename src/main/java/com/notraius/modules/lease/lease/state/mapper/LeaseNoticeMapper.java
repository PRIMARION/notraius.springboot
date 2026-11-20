package com.notraius.modules.lease.lease.state.mapper;

import com.notraius.database.entities.lease.lease.records.LeaseNotice;
import com.notraius.modules.lease.lease.state.dto.response.LeaseNoticeResponse;
import org.springframework.stereotype.Component;

/**
 * The notice's read shape. Its clock lives on the validity row, so the mapper
 * reads {@code issuedAt} and {@code windowEndsAt} there rather than inventing
 * timestamps from the entity's own {@code createdAt}.
 */
@Component
public class LeaseNoticeMapper {

    public LeaseNoticeResponse toResponse(LeaseNotice notice) {
        LeaseNoticeResponse response = new LeaseNoticeResponse();
        response.setId(notice.getId());
        response.setLeaseId(notice.getLease() != null ? notice.getLease().getId() : null);
        response.setType(notice.getType() != null ? notice.getType().getCode() : null);
        response.setReason(notice.getReason());
        response.setResponse(notice.getResponse());
        if (notice.getValidity() != null) {
            response.setStatus(notice.getValidity().getStatus() != null
                    ? notice.getValidity().getStatus().getCode() : null);
            response.setIssuedAt(notice.getValidity().getFromDate());
            response.setWindowEndsAt(notice.getValidity().getToDate());
        }
        response.setRespondedAt(notice.getRespondedAt());
        response.setPreviousValidityToDate(notice.getPreviousValidityToDate());
        response.setVersion(notice.getVersion());
        response.setCreatedAt(notice.getCreatedAt());
        return response;
    }
}
