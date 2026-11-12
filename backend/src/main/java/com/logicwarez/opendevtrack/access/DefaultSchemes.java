/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The default permission scheme of a tenant, made from the sets of {@link ProjectRole} the first time the tenant needs
 * it, in a transaction of its own: the reads of the permissions are read-only.
 */
@Component
class DefaultSchemes {

    static final String NAME = "Default";

    private final PermissionSchemeRepository schemes;

    DefaultSchemes(PermissionSchemeRepository schemes) {
        this.schemes = schemes;
    }

    /** The sets of {@link ProjectRole}: the content of every new default scheme and of every copy. */
    static Map<ProjectRole, Set<String>> fixed() {
        return Arrays.stream(ProjectRole.values())
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ProjectRole::permissions));
    }

    /**
     * Makes the default scheme of the tenant; a {@link org.springframework.dao.DataIntegrityViolationException} if
     * another request made it in the meantime, and the caller reads that one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PermissionSchemeEntity make(String tenantId) {
        return schemes.findByTenantIdAndIsDefaultTrue(tenantId).orElseGet(() -> schemes.saveAndFlush(
                new PermissionSchemeEntity(tenantId, NAME, null, true, fixed(), "system", Instant.now())));
    }
}
