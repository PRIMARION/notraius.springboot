package com.notraius.modules.core.access;

import org.primarion.module.access.authority.AuthorityRepository;
import org.primarion.module.access.consent.service.consent.AccountRoleLookupService;
import org.primarion.module.utils.security.users.UserService;
import org.springframework.stereotype.Component;

/**
 * notraius's binding of the one grantor-ceiling rule — see
 * {@link AccountRoleLookupService} for the decision and its fail-closed
 * guarantees. This class exists only so the host can be wired by type.
 */
@Component
public class NotraiusRoleLookupService extends AccountRoleLookupService {

    public NotraiusRoleLookupService(AuthorityRepository authorityRepository, UserService userService) {
        super(authorityRepository, userService);
    }
}
