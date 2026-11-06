/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An issue as the boards and the backlog show it (module agile): what goes on a card. */
public record IssueSummary(UUID id, String key, long number, String type, String summary, String status, String priority,
                           String assignee, List<String> labels, Instant updatedAt) {
}
