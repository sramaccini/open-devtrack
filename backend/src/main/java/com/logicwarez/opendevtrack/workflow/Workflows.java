/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import com.logicwarez.opendevtrack.shared.Conditions;
import com.logicwarez.opendevtrack.shared.IssueStatuses;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The workflows of a project for the module issue (ADR-10, ADR-20 B): the status of a new issue, the transitions allowed
 * from a status, and whether one of them can run, in the workflow of the type of the issue (the scheme of the project,
 * else its default workflow). The module issue keeps the status; here only the map of its fields is seen. The project
 * permissions given are those of the user ({@code odt.issue.transition} is needed for any transition), and the guards
 * see them through {@code can()}.
 */
@Service
@Transactional(readOnly = true)
public class Workflows {

    /** Permission of the project role needed to run a transition. */
    public static final String TRANSITION = "odt.issue.transition";

    /** Status of a new issue in a project without a workflow (created before the workflows). */
    static final String FALLBACK_STATUS = "todo";

    private final WorkflowRepository workflows;
    private final SchemeRepository schemes;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;
    private final IssueStatuses issues;
    private final Conditions guards;

    Workflows(WorkflowRepository workflows, SchemeRepository schemes, StatusRepository statuses,
              TransitionRepository transitions, IssueStatuses issues, Conditions guards) {
        this.workflows = workflows;
        this.schemes = schemes;
        this.statuses = statuses;
        this.transitions = transitions;
        this.issues = issues;
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

    /**
     * The statuses of every workflow of the project, once per code (a code has one category in a project), in the
     * order of the default workflow and then of the others: for boards, queries and reports.
     */
    public List<Status> statuses(UUID projectId) {
        Map<String, Status> all = new LinkedHashMap<>();
        for (WorkflowEntity w : workflows.findByProjectIdOrderByIsDefaultDescIdAsc(projectId)) {
            for (StatusEntity s : statuses.findByWorkflowIdOrderByPosition(w.id())) {
                all.putIfAbsent(s.code(), new Status(s.code(), s.category(), all.size()));
            }
        }
        return List.copyOf(all.values());
    }

    /** The statuses of the workflow of the type in the project. */
    public List<Status> statuses(UUID projectId, String type) {
        return workflow(projectId, type).map(this::statuses).orElse(List.of());
    }

    /** The first status of the workflow of the type. */
    public String initialStatus(UUID projectId, String type) {
        List<Status> all = statuses(projectId, type);
        return all.isEmpty() ? FALLBACK_STATUS : all.getFirst().code();
    }

    /**
     * The status of an issue in {@code status} once it follows the workflow of {@code type} (ADR-20, B.5): the same
     * when that workflow has it, else its first status of the same category, else its first status.
     */
    public String statusFor(UUID projectId, String type, String status) {
        String category = statuses(projectId).stream().filter(s -> s.code().equals(status)).map(Status::category)
                .findFirst().orElse(null);
        return statusIn(statuses(projectId, type), status, category);
    }

    /**
     * The transitions that can run now from {@code status} in the workflow of the type: the user has
     * {@link #TRANSITION} and the guard is true for the issue as it would be after the transition ({@code issue} without
     * the status, which is added here).
     */
    public List<Transition> available(UUID projectId, String type, String status, Map<String, ?> issue,
                                      Map<String, ?> user, Set<String> permissions) {
        if (!permissions.contains(TRANSITION)) {
            return List.of();
        }
        return workflow(projectId, type).map(w -> transitions.findByWorkflowIdOrderByPosition(w.id()).stream()
                .filter(t -> t.from(status) && guards.allows(t.guardCel(), after(issue, t), user, permissions))
                .map(Workflows::transition).toList()).orElse(List.of());
    }

    /** Whether the transition {@code code} of the workflow of the type can run now, and why not. */
    public Decision decide(UUID projectId, String type, String code, String status, Map<String, ?> issue,
                           Map<String, ?> user, Set<String> permissions) {
        Optional<TransitionEntity> found = workflow(projectId, type)
                .flatMap(w -> transitions.findByWorkflowIdAndCode(w.id(), code));
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

    /** The default workflow of the project. */
    Optional<WorkflowEntity> workflow(UUID projectId) {
        return workflows.findByProjectIdAndIsDefaultTrue(projectId);
    }

    /** The workflow that the scheme maps the type to, else the default. */
    Optional<WorkflowEntity> workflow(UUID projectId, String type) {
        return schemes.findByProjectIdAndIssueType(projectId, type).flatMap(s -> workflows.findById(s.workflowId()))
                .or(() -> workflow(projectId));
    }

    /** The types whose issues follow the workflow: those mapped to it, or for the default those mapped to no other. */
    List<String> types(WorkflowEntity w) {
        Map<String, UUID> mapped = new HashMap<>();
        schemes.findByProjectId(w.projectId()).forEach(s -> mapped.put(s.issueType(), s.workflowId()));
        List<String> types = new ArrayList<>();
        for (String type : issues.types()) {
            UUID to = mapped.get(type);
            if (w.id().equals(to) || (to == null && w.isDefault())) {
                types.add(type);
            }
        }
        return types;
    }

    List<Status> statuses(WorkflowEntity w) {
        return statuses.findByWorkflowIdOrderByPosition(w.id()).stream()
                .map(s -> new Status(s.code(), s.category(), s.position())).toList();
    }

    /** Where an issue in {@code status} (of {@code category}) goes among {@code target}, by the rule of B.5. */
    static String statusIn(List<Status> target, String status, String category) {
        if (target.isEmpty()) {
            return status;
        }
        return target.stream().filter(s -> s.code().equals(status)).findFirst()
                .or(() -> target.stream().filter(s -> s.category().equals(category)).findFirst())
                .orElse(target.getFirst()).code();
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
