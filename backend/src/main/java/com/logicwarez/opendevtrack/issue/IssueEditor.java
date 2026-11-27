/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.IssueApi.IssueRequest;
import com.logicwarez.opendevtrack.issue.IssueEvents.FieldChange;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems.FieldError;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.workflow.Workflows;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The rules of a change of the fields of an issue, the same wherever it is made: the form of the issue
 * ({@link IssueController}) and a move of its card to another lane of a board ({@link Issues#moveTo}). It checks the
 * fields, the custom fields the issue requires and the status of a new type in its workflow (ADR-20); it neither
 * saves nor publishes, which its callers do in their transaction.
 */
@Component
class IssueEditor {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,49}$");
    private static final int MAX_LABELS = 20;
    private static final int MAX_DESCRIPTION = 32_000;

    private final ProjectAccess access;
    private final CustomFields customFields;
    private final Workflows workflows;
    private final WatcherRepository watchers;
    private final CurrentUser user;
    private final IssueRepository issues;
    private final Projects projects;
    private final MovedKeys moved;

    IssueEditor(ProjectAccess access, CustomFields customFields, Workflows workflows, WatcherRepository watchers,
                CurrentUser user, IssueRepository issues, Projects projects, MovedKeys moved) {
        this.issues = issues;
        this.projects = projects;
        this.moved = moved;
        this.access = access;
        this.customFields = customFields;
        this.workflows = workflows;
        this.watchers = watchers;
        this.user = user;
    }

    /**
     * A change of an issue, checked and not applied yet: its fields, the status they lead to, its parent and what
     * differs.
     */
    record Edit(Fields fields, String status, Parent parent, List<FieldChange> changes) {

        /** The issue as the guards would see it after the change. */
        Map<String, Object> guardInput(ProjectRef project, IssueEntity issue) {
            return conditionInput(project, IssueController.key(project, issue), fields, status, issue.reporter());
        }
    }

    /** The change that the request asks of the issue, checked; its version is the caller's to check. */
    Edit edit(ProjectRef project, IssueEntity issue, IssueRequest request, Set<String> permissions)
            throws InvalidField, InvalidCustom {
        Fields fields = fields(project, request, Fields.of(issue));
        // A new type may follow another workflow: the status moves there in the same change (ADR-20, B.5).
        String status = fields.type() == issue.type() ? issue.status()
                : workflows.statusFor(project.id(), fields.type().value(), issue.status());
        checkRequired(project, IssueController.key(project, issue), fields, status, issue.reporter(), permissions);
        Parent before = parentOf(project, issue);
        Parent parent = parent(project, issue, fields.type(), request.parent(), before);
        List<FieldChange> changes = new ArrayList<>(fields.changesFrom(Fields.of(issue)));
        if (!Objects.equals(before.key(), parent.key())) {
            changes.add(new FieldChange("parent", before.key(), parent.key()));
        }
        if (!status.equals(issue.status())) {
            changes.add(new FieldChange("status", issue.status(), status));
        }
        return new Edit(fields, status, parent, changes);
    }

    /** The parent of an issue (ADR-28), by its id and its key; {@link Parent#NONE} without one. */
    record Parent(UUID id, String key) {
        static final Parent NONE = new Parent(null, null);
    }

    /** The parent the issue has now; {@link Parent#NONE} for a new one. */
    Parent parentOf(ProjectRef project, IssueEntity issue) {
        if (issue == null || issue.parentId() == null) {
            return Parent.NONE;
        }
        return issues.findById(issue.parentId()).map(p -> new Parent(p.id(), IssueController.key(project, p)))
                .orElse(Parent.NONE);
    }

    /**
     * The parent an issue of this type would have (ADR-28): the one asked ({@code null} keeps {@code before}, an empty
     * key removes it), checked. An issue that changes level without a parent asked loses the one it had, as a subtask
     * made a task does in Jira. An epic has none; a story, task or bug may have an epic; a subtask has a story, task or
     * bug; always of the same project. An issue with children keeps its level, so that they keep a parent of theirs.
     */
    Parent parent(ProjectRef project, IssueEntity issue, IssueType type, String asked, Parent before)
            throws InvalidField {
        Parent parent = before;
        IssueEntity found = null;
        if (asked != null) {
            String key = asked.strip().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) {
                parent = Parent.NONE;
            } else {
                found = inProject(project, key).orElseThrow(
                        () -> new InvalidField("The parent is an issue of the same project"));
                if (issue != null && found.id().equals(issue.id())) {
                    throw new InvalidField("An issue is not its own parent");
                }
                parent = new Parent(found.id(), IssueController.key(project, found));
            }
        } else if (before.id() != null && issue != null && type.level() != issue.type().level()) {
            parent = Parent.NONE;
        } else if (before.id() != null) {
            found = issues.findById(before.id()).orElse(null);
        }
        if (issue != null && type.level() != issue.type().level() && issues.existsByParentId(issue.id())) {
            throw new InvalidField("An issue with children keeps its level: an epic stays an epic, an issue with"
                    + " subtasks stays a story, task or bug");
        }
        if (type == IssueType.EPIC && parent.id() != null) {
            throw new InvalidField("An epic has no parent");
        }
        if (type == IssueType.SUBTASK && parent.id() == null) {
            throw new InvalidField("A subtask has a parent: a story, task or bug");
        }
        if (found != null && found.type().level() != type.level() + 1) {
            throw new InvalidField(type == IssueType.SUBTASK ? "The parent of a subtask is a story, task or bug"
                    : "The parent of a story, task or bug is an epic");
        }
        return parent;
    }

    /** The issue with this key in the project, also by a key it had before it moved here (ADR-27). */
    private java.util.Optional<IssueEntity> inProject(ProjectRef project, String key) {
        Matcher m = MovedKeys.ISSUE_KEY.matcher(key);
        if (!m.matches()) {
            return java.util.Optional.empty();
        }
        long number = Long.parseLong(m.group(2));
        return projects.find(m.group(1)).flatMap(p -> issues.findByProjectIdAndNumber(p.id(), number)
                        .or(() -> moved.issueOf(p.id(), number).flatMap(issues::findById)))
                .filter(i -> i.projectId().equals(project.id()));
    }

    /** Applies the change to the issue, without saving it. */
    void apply(IssueEntity issue, Edit edit, Instant now) {
        edit.fields().applyTo(issue, now);
        if (!Objects.equals(issue.parentId(), edit.parent().id())) {
            issue.parentTo(edit.parent().id(), now);
        }
        if (!edit.status().equals(issue.status())) {
            issue.moveTo(edit.status(), now);
        }
    }

    /** After the issue is saved: a new assignee, reviewer or reporter watches it. */
    void watch(IssueEntity issue, List<FieldChange> changes, Instant now) {
        if (changes.stream().anyMatch(c -> c.field().equals("reporter"))) {
            watchers.watch(issue.id(), issue.reporter(), now);
        }
        if (changes.stream().anyMatch(c -> c.field().equals("assignee"))) {
            watchAssignee(issue, now);
        }
        if (changes.stream().anyMatch(c -> c.field().equals("reviewer"))) {
            watchReviewer(issue, now);
        }
    }

    /** Whoever is assigned an issue watches it, until they stop. */
    void watchAssignee(IssueEntity issue, Instant now) {
        if (issue.assignee() != null) {
            watchers.watch(issue.id(), issue.assignee(), now);
        }
    }

    /** Whoever is asked to review an issue watches it, as the assignee does (ADR-19). */
    void watchReviewer(IssueEntity issue, Instant now) {
        if (issue.reviewer() != null) {
            watchers.watch(issue.id(), issue.reviewer(), now);
        }
    }

    /** The request that keeps every field of the issue as it is, its custom fields included. */
    static IssueRequest unchanged(IssueEntity issue) {
        return new IssueRequest(issue.type().value(), issue.summary(), issue.description(), issue.priority().value(),
                issue.assignee(), issue.dueOn() == null ? null : issue.dueOn().toString(), issue.reviewer(),
                issue.labels(), null, null, issue.version());
    }

    /** An issue as the conditions see it (ADR-10, ADR-11), also one not saved yet ({@code key} null). */
    static Map<String, Object> conditionInput(ProjectRef project, String key, Fields f, String status,
                                              String reporter) {
        Map<String, Object> issue = new HashMap<>();
        issue.put("key", key);
        issue.put("project", project.key());
        issue.put("type", f.type() == null ? null : f.type().value());
        issue.put("summary", f.summary());
        issue.put("description", f.description());
        issue.put("status", status);
        issue.put("priority", f.priority() == null ? null : f.priority().value());
        issue.put("assignee", f.assignee());
        issue.put("reporter", reporter);
        issue.put("dueOn", f.dueOn() == null ? null : f.dueOn().toString());
        issue.put("reviewer", f.reviewer());
        issue.put("labels", f.labels() == null ? List.of() : f.labels());
        issue.put("custom", f.custom());
        return issue;
    }

    /** The custom fields that the issue, as it would be saved, requires and lacks. */
    void checkRequired(ProjectRef project, String key, Fields fields, String status, String reporter,
                       Set<String> permissions) throws InvalidCustom {
        List<FieldError> missing = customFields.missing(project.id(),
                conditionInput(project, key, fields, status, reporter), IssueController.userInput(user), permissions);
        if (!missing.isEmpty()) {
            throw new InvalidCustom(missing);
        }
    }

    /** The fields of a request, checked; type and priority default to task and medium. */
    record Fields(IssueType type, String summary, String description, Priority priority, String assignee,
                  LocalDate dueOn, String reviewer, List<String> labels, Map<String, Object> custom) {

        void applyTo(IssueEntity issue, Instant now) {
            issue.edit(type, summary, description, priority, assignee, dueOn, reviewer, labels, custom, now);
        }

        static final Fields EMPTY = new Fields(null, null, null, null, null, null, null, List.of(), Map.of());

        static Fields of(IssueEntity issue) {
            return new Fields(issue.type(), issue.summary(), issue.description(), issue.priority(), issue.assignee(),
                    issue.dueOn(), issue.reviewer(), issue.labels(), issue.custom());
        }

        /** The fields that differ from {@code before}, in the order of the form. */
        List<FieldChange> changesFrom(Fields before) {
            List<FieldChange> changes = new ArrayList<>();
            change(changes, "type", before.type == null ? null : before.type.value(), type == null ? null : type.value());
            change(changes, "summary", before.summary, summary);
            change(changes, "priority", before.priority == null ? null : before.priority.value(),
                    priority == null ? null : priority.value());
            change(changes, "assignee", before.assignee, assignee);
            change(changes, "reviewer", before.reviewer, reviewer);
            change(changes, "due", before.dueOn == null ? null : before.dueOn.toString(),
                    dueOn == null ? null : dueOn.toString());
            change(changes, "labels", text(before.labels), text(labels));
            change(changes, "description", before.description, description);
            Set<String> keys = new TreeSet<>(before.custom.keySet());
            keys.addAll(custom.keySet());
            for (String key : keys) {
                change(changes, "custom." + key, CustomFields.text(before.custom.get(key)),
                        CustomFields.text(custom.get(key)));
            }
            return changes;
        }

        private static void change(List<FieldChange> changes, String field, String from, String to) {
            if (!Objects.equals(from, to)) {
                changes.add(new FieldChange(field, from, to));
            }
        }

        private static String text(List<String> labels) {
            return labels == null || labels.isEmpty() ? null : String.join(", ", labels);
        }
    }

    static final class InvalidField extends Exception {
        InvalidField(String message) {
            super(message);
        }
    }

    /** Custom fields that are not valid, with the errors on each (ADR-11). */
    static final class InvalidCustom extends Exception {
        static final String TITLE = "Some custom fields are not valid";
        final transient List<FieldError> errors;

        InvalidCustom(List<FieldError> errors) {
            super(TITLE, null, false, false);
            this.errors = errors;
        }
    }

    /** The fields of a request, checked, on top of {@code before}; without {@code custom} its values stay. */
    Fields fields(ProjectRef project, IssueRequest request, Fields before) throws InvalidField, InvalidCustom {
        IssueType type = request.type() == null ? IssueType.TASK : IssueType.parse(request.type())
                .orElseThrow(() -> new InvalidField("The type is one of " + String.join(", ", IssueType.names())));
        Priority priority = request.priority() == null ? Priority.MEDIUM : Priority.parse(request.priority())
                .orElseThrow(() -> new InvalidField("The priority is one of " + String.join(", ", Priority.names())));
        if (request.summary() == null || request.summary().isBlank() || request.summary().strip().length() > 255) {
            throw new InvalidField("The summary is required, at most 255 characters");
        }
        if (request.description() != null && request.description().length() > MAX_DESCRIPTION) {
            throw new InvalidField("The description is at most " + MAX_DESCRIPTION + " characters");
        }
        String assignee = blankToNull(request.assignee());
        if (assignee != null && access.role(project.id(), assignee).isEmpty()) {
            throw new InvalidField("The assignee must be a member of the project");
        }
        String reviewer = blankToNull(request.reviewer());
        if (reviewer != null && access.role(project.id(), reviewer).isEmpty()) {
            throw new InvalidField("The reviewer must be a member of the project");
        }
        if (reviewer != null && reviewer.equals(assignee)) {
            throw new InvalidField("The reviewer cannot be the assignee");
        }
        LocalDate dueOn;
        try {
            dueOn = blankToNull(request.dueOn()) == null ? null : LocalDate.parse(request.dueOn().strip());
        } catch (DateTimeParseException e) {
            throw new InvalidField("The due date is a day as YYYY-MM-DD");
        }
        Map<String, Object> custom = before.custom();
        if (request.custom() != null) {
            CustomFields.Checked checked = customFields.check(project.id(), request.custom(), before.custom());
            if (!checked.errors().isEmpty()) {
                throw new InvalidCustom(checked.errors());
            }
            custom = checked.values();
        }
        return new Fields(type, request.summary().strip(), blankToNull(request.description()), priority, assignee,
                dueOn, reviewer, labels(request.labels()), custom);
    }

    /** Labels in lower case, without repetitions, in the order given. */
    private static List<String> labels(List<String> given) throws InvalidField {
        if (given == null) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (String label : given) {
            String l = label == null ? "" : label.strip().toLowerCase(Locale.ROOT);
            if (!LABEL.matcher(l).matches()) {
                throw new InvalidField("A label is 1 to 50 letters, digits, dots, dashes or underscores, without spaces");
            }
            labels.add(l);
        }
        if (labels.size() > MAX_LABELS) {
            throw new InvalidField("At most " + MAX_LABELS + " labels");
        }
        return new ArrayList<>(labels);
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
