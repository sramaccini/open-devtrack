/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workflow of a project for the module issue (ADR-10): the status of a new issue, the transitions allowed from a
 * status, and whether one of them can run. The module issue keeps the status; here only the map of its fields is seen.
 * The project permissions given are those of the user ({@code odt.issue.transition} is needed for any transition), and
 * the guards see them through {@code can()}.
 */
@Service
@Transactional(readOnly = true)
public class Workflows {

    /** Permission of the project role needed to run a transition. */
    public static final String TRANSITION = "odt.issue.transition";

    /** Status of a new issue in a project without a workflow (created before the workflows). */
    static final String FALLBACK_STATUS = "todo";

    private final WorkflowRepository workflows;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final Guards guards;

    Workflows(WorkflowRepository workflows, StatusRepository statuses, TransitionRepository transitions, Guards guards) {
        this.workflows = workflows;
        this.statuses = statuses;
        this.transitions = transitions;
        this.guards = guards;
    }

    public record Status(String code, String category, int position) {
    }

    /** A transition; {@code from} is null when it is allowed from every status. */
    public record Transition(String code, String from, String to) {
    }

    /** Outcome of {@link #decide}: the transition when {@code ALLOWED}. */
    public record Decision(Outcome outcome, Transition transition) {

        public enum Outcome { ALLOWED, UNKNOWN, NOT_FROM_HERE, NOT_PERMITTED, GUARD_FALSE }
    }

    public List<Status> statuses(UUID projectId) {
        return workflow(projectId).map(w -> statuses.findByWorkflowIdOrderByPosition(w.id()).stream()
                .map(s -> new Status(s.code(), s.category(), s.position())).toList()).orElse(List.of());
    }

    /** The first status of the workflow of the project. */
    public String initialStatus(UUID projectId) {
        List<Status> all = statuses(projectId);
        return all.isEmpty() ? FALLBACK_STATUS : all.getFirst().code();
    }

    /**
     * The transitions that can run now from {@code status}: the user has {@link #TRANSITION} and the guard is true for
     * the issue as it would be after the transition ({@code issue} without the status, which is added here).
     */
    public List<Transition> available(UUID projectId, String status, Map<String, ?> issue, Map<String, ?> user,
                                      Set<String> permissions) {
        if (!permissions.contains(TRANSITION)) {
            return List.of();
        }
        return workflow(projectId).map(w -> transitions.findByWorkflowIdOrderByPosition(w.id()).stream()
                .filter(t -> t.from(status) && guards.allows(t.guardCel(), after(issue, t), user, permissions))
                .map(Workflows::transition).toList()).orElse(List.of());
    }

    /** Whether the transition {@code code} can run now, and why not. */
    public Decision decide(UUID projectId, String code, String status, Map<String, ?> issue, Map<String, ?> user,
                           Set<String> permissions) {
        Optional<TransitionEntity> found = workflow(projectId).flatMap(w -> transitions.findByWorkflowIdAndCode(w.id(), code));
        if (found.isEmpty()) {
            return new Decision(Decision.Outcome.UNKNOWN, null);
        }
        TransitionEntity t = found.get();
        Transition transition = transition(t);
        if (!permissions.contains(TRANSITION)) {
            return new Decision(Decision.Outcome.NOT_PERMITTED, transition);
        }
        if (!t.from(status)) {
            return new Decision(Decision.Outcome.NOT_FROM_HERE, transition);
        }
        if (!guards.allows(t.guardCel(), after(issue, t), user, permissions)) {
            return new Decision(Decision.Outcome.GUARD_FALSE, transition);
        }
        return new Decision(Decision.Outcome.ALLOWED, transition);
    }

    Optional<WorkflowEntity> workflow(UUID projectId) {
        return workflows.findByProjectId(projectId);
    }

    private static Map<String, Object> after(Map<String, ?> issue, TransitionEntity t) {
        Map<String, Object> after = new HashMap<>(issue);
        after.put("status", t.toStatus());
        return after;
    }

    private static Transition transition(TransitionEntity t) {
        return new Transition(t.code(), t.fromStatus(), t.toStatus());
    }
}
