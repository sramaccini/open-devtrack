/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Events of the module collaboration, published inside the transaction of the change; listeners run after its commit. */
public final class CommentEvents {

    private CommentEvents() {
    }

    /**
     * A comment written ({@code edited} false) or edited. {@code mentioned} are the members newly mentioned by it, never
     * its author; {@code excerpt} is the beginning of its text.
     */
    public record CommentPosted(UUID commentId, UUID issueId, UUID projectId, String issueKey, String by, Instant at,
                                boolean edited, List<String> mentioned, String excerpt) {
    }
}
