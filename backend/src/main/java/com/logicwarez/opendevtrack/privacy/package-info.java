/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/**
 * The data of a person (ADR-24, O13; ASVS 8.3.2): the export of everything the modules keep about a user of the
 * tenant, and their removal, where the user's name becomes a pseudonym of the tenant ({@code former-user-<n>}).
 */
@ApplicationModule(displayName = "Privacy")
package com.logicwarez.opendevtrack.privacy;

import org.springframework.modulith.ApplicationModule;
