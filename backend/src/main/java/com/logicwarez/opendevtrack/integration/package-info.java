/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/**
 * Outgoing webhooks of the projects (ADR-21, B): it listens to the events of the other modules and posts them, signed,
 * from an outbox. No module depends on it.
 */
@ApplicationModule(displayName = "Integration")
package com.logicwarez.opendevtrack.integration;

import org.springframework.modulith.ApplicationModule;
