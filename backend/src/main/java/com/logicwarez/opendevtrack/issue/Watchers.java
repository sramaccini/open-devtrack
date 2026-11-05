/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The watchers of the issues for the other modules: collaboration adds the people mentioned in a comment, notification
 * reads who to tell. No check of the user of the session: the callers decide who may be added.
 */
@Service
@Transactional(readOnly = true)
public class Watchers {

    private final WatcherRepository watchers;

    Watchers(WatcherRepository watchers) {
        this.watchers = watchers;
    }

    /** The usernames that watch the issue, in order. */
    public List<String> of(UUID issueId) {
        return watchers.findByIssueIdOrderByUsername(issueId).stream().map(WatcherEntity::username).toList();
    }

    /** Adds the watcher unless it is already there, inside the transaction of the caller. */
    @Transactional
    public void watch(UUID issueId, String username, Instant at) {
        watchers.watch(issueId, username, at);
    }
}
