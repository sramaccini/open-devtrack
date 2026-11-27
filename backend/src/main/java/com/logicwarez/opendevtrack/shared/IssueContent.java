/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a module keeps about one issue, for the export of the issue: every module that stores the work done on an
 * issue (comments, attachments, history) implements it, and the module {@code issue} asks each of them, as
 * {@link PersonalData} does for a user. A module reads only its own tables; the caller has checked that the user sees
 * the issue.
 */
public interface IssueContent {

    /**
     * The parts of the export of this module for the issue, each a list of rows, oldest first ({@code "comments"} →
     * its comments). Instants as {@link java.time.Instant}.
     */
    Map<String, List<Map<String, Object>>> content(UUID issueId);
}
