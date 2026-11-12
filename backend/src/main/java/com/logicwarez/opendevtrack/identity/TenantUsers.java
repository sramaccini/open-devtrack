/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.identity;

import com.logicwarez.authz.rbac.directory.TenantDirectory;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The users of a tenant, read from the directory of authz-library ({@link TenantDirectory}, A5): the active users with
 * a role in the tenant, read only, for project members, assignments, mentions and the mails.
 */
@Service
public class TenantUsers {

    private final TenantDirectory directory;
    private final CurrentUser user;

    TenantUsers(TenantDirectory directory, CurrentUser user) {
        this.directory = directory;
        this.user = user;
    }

    /** Active users of the tenant whose user name or email contains the text (all with an empty text), at most limit. */
    public List<TenantUser> search(String text, int limit) {
        return directory.search(user.tenantId(), text, limit).stream()
                .map(u -> new TenantUser(u.username(), u.email())).toList();
    }

    /** The active user with this name, if they belong to the tenant of the session. */
    public Optional<TenantUser> find(String username) {
        return find(user.tenantId(), username);
    }

    /** The active user with this name in the tenant, also for jobs that run without a session (the mails of ADR-18). */
    public Optional<TenantUser> find(String tenantId, String username) {
        return directory.find(tenantId, username).map(u -> new TenantUser(u.username(), u.email()));
    }
}
