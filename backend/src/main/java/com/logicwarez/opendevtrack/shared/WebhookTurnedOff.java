/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * A webhook of the project was turned off: its deliveries failed for three days in a row (ADR-21, B11). Published by
 * {@code integration}, read by {@code notification} for a message to the admins of the project; here so that no module
 * depends on {@code integration}.
 */
public record WebhookTurnedOff(UUID projectId, UUID webhookId, String name, Instant at) {
}
