/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.identity;

import com.logicwarez.authz.rbac.admin.AdminApi.TenantUserView;
import com.logicwarez.authz.rbac.admin.AdminScope;
import com.logicwarez.authz.rbac.admin.RbacAdminService;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The users of the tenant of the session, read from authz-rbac: for project members, assignments and mentions.
 *
 * <p>authz-library lists the users of a tenant only for its administrators. Until it offers a read-only list to any
 * user of the tenant (request A5), this service asks with the scope of an administrator of the tenant of the session,
 * for reading only: it never sees other tenants and never changes anything.
 */
@Service
public class TenantUsers {

    private static final int PAGE = 200;

    private final RbacAdminService rbac;
    private final CurrentUser user;

    TenantUsers(RbacAdminService rbac, CurrentUser user) {
        this.rbac = rbac;
        this.user = user;
    }

    /** Active users of the tenant whose user name or email contains the text (all with an empty text), at most limit. */
    public List<TenantUser> search(String text, int limit) {
        String needle = text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
        List<TenantUser> found = new ArrayList<>();
        for (TenantUserView view : all()) {
            if (view.active() && (view.username().toLowerCase(Locale.ROOT).contains(needle)
                    || (view.email() != null && view.email().toLowerCase(Locale.ROOT).contains(needle)))) {
                found.add(new TenantUser(view.username(), view.email()));
                if (found.size() == limit) {
                    break;
                }
            }
        }
        return found;
    }

    /** The active user with this name, if they belong to the tenant of the session. */
    public Optional<TenantUser> find(String username) {
        return all().stream().filter(v -> v.active() && v.username().equals(username))
                .map(v -> new TenantUser(v.username(), v.email())).findFirst();
    }

    private List<TenantUserView> all() {
        String tenant = user.tenantId();
        AdminScope reader = new AdminScope(user.username(), tenant, Set.of(AdminScope.MANAGE));
        List<TenantUserView> users = new ArrayList<>();
        for (int offset = 0; ; offset += PAGE) {
            List<TenantUserView> page = rbac.tenantUsers(reader, tenant, PAGE, offset);
            users.addAll(page);
            if (page.size() < PAGE) {
                return users;
            }
        }
    }
}
