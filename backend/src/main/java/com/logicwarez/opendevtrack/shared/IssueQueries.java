/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * ODQL queries (ADR-16) for the modules that the module {@code search} depends on, such as the filter of a board in
 * {@code agile}: they ask through this interface, which {@code search} implements, and so never depend on it. A query
 * runs for the user of the request ({@code currentUser()}, the projects they see).
 */
public interface IssueQueries {

    /** Where a query is wrong: the position (from 1) and why. */
    record Wrong(int position, String reason) {
    }

    /** Why the query is wrong for the user, or empty when it is right. */
    Optional<Wrong> check(String query);

    /** The keys of the issues of the project the query finds, or the reason it is wrong (no longer right). */
    Matches keys(UUID projectId, String query);

    /** What a query found: the keys, or where it is wrong. */
    record Matches(Set<String> keys, Wrong wrong) {
    }
}
