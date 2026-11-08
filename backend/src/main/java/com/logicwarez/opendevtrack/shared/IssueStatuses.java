/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The statuses of the issues for the module {@code workflow}, which the module {@code issue} depends on (ADR-20): when a
 * status is removed from a workflow, or a type of issue moves to another workflow, its issues move to another status
 * through this interface, which {@code issue} implements, and so {@code workflow} never depends on {@code issue}.
 */
public interface IssueStatuses {

    /** The types of issue, as the API names them ({@code task}, {@code bug}…). */
    List<String> types();

    /** How many issues of the project, of one of the types, are in the status. */
    long count(UUID projectId, Collection<String> types, String status);

    /**
     * Moves every issue of the project, of one of the types, in {@code from} to {@code to}, as a change of the user of
     * the request (one change of the status per issue, in the history); returns how many moved.
     */
    int move(UUID projectId, Collection<String> types, String from, String to);
}
