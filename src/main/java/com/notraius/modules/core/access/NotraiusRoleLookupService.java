package com.notraius.modules.core.access;

import org.primarion.module.utils.security.users.UserService;
import org.primarion.module.access.authority.Authority;
import org.primarion.module.access.authority.AuthorityRepository;
import org.primarion.module.access.consent.service.consent.RoleLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Authority level of an account, as seen by the consent/seat machinery
 * ("you cannot delegate what you don't have", the grantor ceiling).
 * <p>
 * Single source — the <b>global account role</b>
 * ({@code PERSONAL}/{@code ENTERPRISE}/…), resolved through primarion (the
 * identity provider) via the shared {@link UserService} cache. Account roles
 * exist ONLY there, and consents/seat delegations are decided between
 * accounts. Fail-closed: an unresolvable account contributes -1.
 * <p>
 * This is the logistic estate's default binding, carried over to notraius
 * unchanged — the resolution logic has nothing logistic-specific in it.
 */
@Component
public class NotraiusRoleLookupService implements RoleLookupService {

    private static final Logger log = LoggerFactory.getLogger(NotraiusRoleLookupService.class);

    private final AuthorityRepository authorityRepository;
    private final UserService userService;

    public NotraiusRoleLookupService(AuthorityRepository authorityRepository,
                               UserService userService) {
        this.authorityRepository = authorityRepository;
        this.userService = userService;
    }

    @Override
    public int lookupLevel(UUID userId) {
        if (userId == null) {
            return -1;
        }
        // Grantor-kill switch: a terminated account (SUSPENDED/BANNED/DELETED)
        // resolves to -1 — a killed account keeps no authority.
        Boolean terminated = isTerminated(userId);
        if (Boolean.TRUE.equals(terminated)) {
            return -1;
        }
        return accountRoleLevel(userId);
    }

    /** True when primarion answers with a terminated account; null when unknown. */
    private Boolean isTerminated(UUID userId) {
        try {
            return userService.findById(userId)
                    .map(org.primarion.module.utils.security.identity.ResolvedUser::isTerminated)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Could not resolve account state for user {}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** The account's global role level (PERSONAL/ENTERPRISE/…); -1 when unknown. */
    private int accountRoleLevel(UUID userId) {
        try {
            return userService.findById(userId)
                    .map(org.primarion.module.utils.security.identity.ResolvedUser::roleCode)
                    .map(this::levelOf)
                    .orElse(-1);
        } catch (Exception e) {
            log.debug("Could not resolve account role for user {}: {}", userId, e.getMessage());
            return -1;
        }
    }

    /** Resolves an authority code to its seeded level; -1 for null/unknown codes. */
    private int levelOf(String authorityCode) {
        if (authorityCode == null || authorityCode.isBlank()) {
            return -1;
        }
        return authorityRepository.findByCode(authorityCode.trim().toUpperCase(Locale.ROOT))
                .map(Authority::getLevel)
                .orElse(-1);
    }
}
