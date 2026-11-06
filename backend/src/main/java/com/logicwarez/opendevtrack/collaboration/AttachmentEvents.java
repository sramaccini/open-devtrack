/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.time.Instant;
import java.util.UUID;

/** Events of the attachments of an issue (ADR-14), published in the transaction that adds or removes one. */
public final class AttachmentEvents {

    private AttachmentEvents() {
    }

    public record AttachmentAdded(UUID attachmentId, UUID issueId, UUID projectId, String issueKey, String filename,
                                  String by, Instant at) {
    }

    public record AttachmentRemoved(UUID attachmentId, UUID issueId, UUID projectId, String issueKey, String filename,
                                    String by, Instant at) {
    }
}
