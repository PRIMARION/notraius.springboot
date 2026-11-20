package com.notraius.modules.core.core;

import org.primarion.module.app.access.activity.authority.AuthorityRepository;
import org.primarion.module.app.access.consent.consent.service.subscription.consent.account.AccountRoleLookupService;
import org.primarion.module.utils.utils.security.security.users.user.UserService;
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
