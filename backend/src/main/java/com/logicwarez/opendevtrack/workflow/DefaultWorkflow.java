/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import com.logicwarez.opendevtrack.project.ProjectCreated;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Gives every new project its copy of the built-in workflow (ADR-10): To do → In progress → Done, and Done → To do. It
 * listens inside the transaction that creates the project, not after it: a project never exists without a workflow, and
 * its first issue can be created at once.
 */
@Component
class DefaultWorkflow {

    static final String NAME = "Default";

    /** Code, category. */
    static final List<List<String>> STATUSES = List.of(
            List.of("todo", "todo"),
            List.of("in_progress", "in_progress"),
            List.of("done", "done"));

    /** Code, from, to. */
    static final List<List<String>> TRANSITIONS = List.of(
            List.of("start", "todo", "in_progress"),
            List.of("finish", "in_progress", "done"),
            List.of("reopen", "done", "todo"));

    private final WorkflowRepository workflows;
    private final StatusRepository statuses;
    private final TransitionRepository transitions;

    DefaultWorkflow(WorkflowRepository workflows, StatusRepository statuses, TransitionRepository transitions) {
        this.workflows = workflows;
        this.statuses = statuses;
        this.transitions = transitions;
    }

    @EventListener
    void on(ProjectCreated event) {
        WorkflowEntity workflow = workflows.save(new WorkflowEntity(event.tenantId(), event.projectId(), NAME));
        for (int i = 0; i < STATUSES.size(); i++) {
            statuses.save(new StatusEntity(workflow.id(), STATUSES.get(i).get(0), STATUSES.get(i).get(1), i));
        }
        for (int i = 0; i < TRANSITIONS.size(); i++) {
            List<String> t = TRANSITIONS.get(i);
            transitions.save(new TransitionEntity(workflow.id(), t.get(0), t.get(1), t.get(2), i));
        }
    }
}
