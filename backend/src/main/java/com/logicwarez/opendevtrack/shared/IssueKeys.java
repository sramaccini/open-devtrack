/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import java.util.Optional;

/**
 * The keys of the issues for the modules that cannot depend on the module issue (ADR-27): an issue moved to another
 * project keeps its old keys, so the project of a key ({@code WEB-3}) is the one the issue is in now.
 */
public interface IssueKeys {

    /**
     * The key of the project the issue with this key is in now, in the tenant of the session: its own prefix for an
     * issue that never moved, that of the target for an old key. Empty when there is no such issue.
     */
    Optional<String> projectKeyOf(String issueKey);
}
