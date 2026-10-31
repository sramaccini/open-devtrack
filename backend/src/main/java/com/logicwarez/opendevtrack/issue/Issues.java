/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues for the other modules (comments, changelog): found by their key in the tenant of the session. */
@Service
@Transactional(readOnly = true)
public class Issues {

    private static final Pattern ISSUE_KEY = Pattern.compile("^([A-Z][A-Z0-9]{1,9})-([1-9][0-9]{0,17})$");

    private final IssueRepository issues;
    private final Projects projects;

    Issues(IssueRepository issues, Projects projects) {
        this.issues = issues;
        this.projects = projects;
    }

    /** The issue with this key ({@code ODT-42}); empty also when its project belongs to another tenant. */
    public Optional<IssueRef> find(String issueKey) {
        return findEntity(issueKey).map(f -> new IssueRef(f.issue().id(), f.project().id(), issueKey));
    }

    record Found(ProjectRef project, IssueEntity issue) {
    }

    Optional<Found> findEntity(String issueKey) {
        Matcher m = ISSUE_KEY.matcher(issueKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        return projects.find(m.group(1)).flatMap(p -> issues.findByProjectIdAndNumber(p.id(), Long.parseLong(m.group(2)))
                .map(i -> new Found(p, i)));
    }
}
