/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The user of the request and their current tenant, from the session of authz-library. The one place where the modules
 * read the tenant (ADR-09): never from a parameter of the browser.
 */
@Component
public class CurrentUser {

    /** User name and tenant of the session; {@link AccessDeniedException} without a session (a tool token has none). */
    public SessionContext session() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof SessionAuthentication session && session.getPrincipal().tenantId() != null) {
            return session.getPrincipal();
        }
        throw new AccessDeniedException("A session with a tenant is needed");
    }

    public String username() {
        return session().username();
    }

    public String tenantId() {
        return session().tenantId();
    }

    /** A global permission of the session (authz-library), for example {@code odt.project.create}. */
    public boolean can(String permission) {
        return session().hasPermission(permission);
    }
}
