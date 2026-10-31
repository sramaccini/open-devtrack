/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.identity;

/** A user of the tenant, as shown in pickers and lists. */
public record TenantUser(String username, String email) {
}
