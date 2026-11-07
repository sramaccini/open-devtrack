/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static com.logicwarez.opendevtrack.search.Docs.DOC;
import static com.logicwarez.opendevtrack.search.Docs.ISSUE_KEY;
import static com.logicwarez.opendevtrack.search.Docs.PROJECT_ID;
import static com.logicwarez.opendevtrack.search.Docs.TENANT_ID;

import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.search.Odql.OdqlException;
import com.logicwarez.opendevtrack.search.OdqlCompiler.Compiled;
import com.logicwarez.opendevtrack.shared.IssueQueries;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * The ODQL queries asked by the modules that {@code search} depends on (the filter of a board), on the index of the
 * issues: what it finds is as fresh as the index, written just after each commit.
 */
@Component
class Queries implements IssueQueries {

    private final Searches searches;
    private final DSLContext sql;
    private final CurrentUser user;

    Queries(Searches searches, DSLContext sql, CurrentUser user) {
        this.searches = searches;
        this.sql = sql;
        this.user = user;
    }

    @Override
    public Optional<Wrong> check(String query) {
        try {
            searches.compile(query, searches.visible());
            return Optional.empty();
        } catch (OdqlException e) {
            return Optional.of(new Wrong(e.position(), e.getMessage()));
        }
    }

    @Override
    public Matches keys(UUID projectId, String query) {
        Compiled compiled;
        try {
            compiled = searches.compile(query, searches.visible());
        } catch (OdqlException e) {
            return new Matches(null, new Wrong(e.position(), e.getMessage()));
        }
        return new Matches(new HashSet<>(sql.select(ISSUE_KEY).from(DOC)
                .where(TENANT_ID.eq(user.tenantId())).and(PROJECT_ID.eq(projectId)).and(compiled.where())
                .fetch(ISSUE_KEY)), null);
    }
}
