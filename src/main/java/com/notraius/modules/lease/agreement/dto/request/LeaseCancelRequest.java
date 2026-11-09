package com.notraius.modules.lease.agreement.dto.request;

import lombok.Getter;
import lombok.Setter;

/**
 * The pre-commitment exit (PLAN 6.0/6.1). A reason is optional for the parties
 * and mandatory in spirit for the logs: whoever walked away from a funded
 * intent leaves a sentence the other side can read.
 */
@Getter
@Setter
public class LeaseCancelRequest {

    private String reason;
}
