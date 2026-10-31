/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.identity;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Users of the tenant for the pickers of the screens (lookup fields: members, assignee, mentions). */
@RestController
class UserController {

    private final TenantUsers users;

    UserController(TenantUsers users) {
        this.users = users;
    }

    @GetMapping("/api/users")
    @PreAuthorize("hasAuthority('odt.user.browse')")
    List<TenantUser> search(@RequestParam(name = "q", defaultValue = "") String text,
                            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return users.search(text, Math.clamp(limit, 1, 50));
    }
}
