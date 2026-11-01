/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The guards of ADR-10 and the conditions of ADR-11 in the documentation, evaluated as the transitions do. */
class ConditionsTest {

    private final Conditions guards = new Conditions();

    private static Map<String, Object> issue(String assignee, List<String> labels) {
        Map<String, Object> issue = new HashMap<>();
        issue.put("key", "ODT-1");
        issue.put("type", "bug");
        issue.put("priority", "high");
        issue.put("assignee", assignee);
        issue.put("labels", labels);
        return issue;
    }

    private final Map<String, Object> user = Map.of("username", "luisa", "roles", List.of("ODT_MEMBER"));

    @Test
    void theExamplesOfTheAdr() {
        String assigned = "issue.assignee != null && can('odt.issue.transition')";
        assertThat(guards.check(assigned)).isEmpty();
        assertThat(guards.allows(assigned, issue("luisa", List.of()), user, Set.of("odt.issue.transition"))).isTrue();
        assertThat(guards.allows(assigned, issue(null, List.of()), user, Set.of("odt.issue.transition"))).isFalse();
        assertThat(guards.allows(assigned, issue("luisa", List.of()), user, Set.of())).isFalse();
    }

    @Test
    void fieldsListsAndTheUser() {
        assertThat(guards.allows("issue.type != 'bug' || issue.labels.exists(l, l == 'verified')",
                issue(null, List.of("ui", "verified")), user, Set.of())).isTrue();
        assertThat(guards.allows("issue.assignee == user.username", issue("luisa", List.of()), user, Set.of())).isTrue();
        assertThat(guards.allows("'ODT_MEMBER' in user.roles", issue(null, List.of()), user, Set.of())).isTrue();
        assertThat(guards.allows("", issue(null, List.of()), user, Set.of())).isTrue();
    }

    @Test
    void wrongGuardsAreRefusedOrFalse() {
        assertThat(guards.check("issue.assignee !=")).get().asString().contains("ERROR");
        // Not a boolean: refused when the type is known, never true when it is dyn.
        assertThat(guards.check("1 + 1")).isPresent();
        assertThat(guards.allows("issue.summary", issue(null, List.of()), user, Set.of())).isFalse();
        assertThat(guards.check("cannot('x')")).isPresent();
        assertThat(guards.check("x".repeat(1001))).isPresent();
        // A field that is not there: false, not an error.
        assertThat(guards.allows("issue.sprint == 'S1'", issue(null, List.of()), user, Set.of())).isFalse();
    }

    @Test
    void theCustomFieldsOfAdr11() {
        Map<String, Object> issue = issue(null, List.of());
        issue.put("custom", Map.of("customer", "ACME", "points", 3));
        assertThat(guards.allows("issue.type == 'bug'", issue, user, Set.of())).isTrue();
        assertThat(guards.allows("has(issue.custom.customer)", issue, user, Set.of())).isTrue();
        assertThat(guards.allows("has(issue.custom.reviewer)", issue, user, Set.of())).isFalse();
        assertThat(guards.allows("issue.custom.points > 2", issue, user, Set.of())).isTrue();
        assertThat(guards.allows("true", issue, user, Set.of())).isTrue();
    }
}
