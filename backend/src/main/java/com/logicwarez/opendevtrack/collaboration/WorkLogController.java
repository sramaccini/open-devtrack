/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.collaboration.WorkLogEvents.WorkLogChanged;
import com.logicwarez.opendevtrack.collaboration.WorkLogEvents.WorkLogDeleted;
import com.logicwarez.opendevtrack.collaboration.WorkLogEvents.WorkLogged;
import com.logicwarez.opendevtrack.issue.IssueRef;
import com.logicwarez.opendevtrack.issue.Issues;
import com.logicwarez.opendevtrack.issue.TimeTracking;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.shared.Durations;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The work log of an issue (ADR-30), as in Jira: whoever sees the issue reads it, {@code odt.worklog.add} logs work and
 * changes or deletes one's own, {@code odt.worklog.manage} those of others. Each change moves the time spent of the
 * issue, and its remaining estimate the other way unless the request sets it.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class WorkLogController {

    private static final String ISSUE_NOT_FOUND = "Issue not found";
    private static final String NOT_FOUND = "Work log not found";
    private static final int MAX_COMMENT = 32_000;

    private final Issues issues;
    private final WorkLogRepository workLogs;
    private final IssueWorkLogs shown;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final ApplicationEventPublisher events;
    private final Durations durations;
    private final ZoneId zone;

    WorkLogController(Issues issues, WorkLogRepository workLogs, IssueWorkLogs shown, ProjectAccess access,
                      CurrentUser user, ApplicationEventPublisher events, TimeTracking time,
                      @Value("${odt.reporting.zone:UTC}") String zone) {
        this.issues = issues;
        this.workLogs = workLogs;
        this.shown = shown;
        this.access = access;
        this.user = user;
        this.events = events;
        this.durations = time.durations();
        this.zone = ZoneId.of(zone);
    }

    /** {@code startedLocal} is {@code started} in the zone of the installation, as a date and time field takes it. */
    record WorkLogView(UUID id, String author, Instant started, String startedLocal, String timeSpent, int minutes,
                       String comment, Instant createdAt, Instant updatedAt, long version,
                       @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /**
     * {@code timeSpent} as {@code 1d 2h 30m}; {@code started} an instant or a local date and time (now when absent);
     * {@code remainingEstimate} sets what remains, else it moves by the work.
     */
    record WorkLogRequest(String timeSpent, String started, String comment, String remainingEstimate, Long version) {
    }

    /** The settings of time tracking, for the screens. */
    record TimeTrackingSettings(int hoursPerDay, int daysPerWeek) {
    }

    @ApiDoc(value = "The settings of time tracking of the installation", answer = TimeTrackingSettings.class)
    @GetMapping("/api/time-tracking")
    TimeTrackingSettings settings() {
        return new TimeTrackingSettings(durations.hoursPerDay(), durations.daysPerWeek());
    }

    @ApiDoc(value = "The work log of an issue, by when the work started",
            answer = WorkLogView.class, list = true, project = "odt.issue.browse")
    @GetMapping("/api/issues/{issueKey}/worklogs")
    ResponseEntity<?> list(@PathVariable String issueKey) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its issues");
        }
        return ResponseEntity.ok(shown.of(issue.get().id(), permissions).stream().map(this::view).toList());
    }

    @ApiDoc(value = "Logs work on an issue", answer = WorkLogView.class, status = 201, project = "odt.worklog.add")
    @PostMapping("/api/issues/{issueKey}/worklogs")
    @Transactional
    ResponseEntity<?> add(@PathVariable String issueKey, @RequestBody WorkLogRequest request) {
        Optional<IssueRef> issue = issues.find(issueKey);
        if (issue.isEmpty()) {
            return Problems.notFound(ISSUE_NOT_FOUND);
        }
        Set<String> permissions = permissions(issue.get());
        if (!permissions.contains(IssueWorkLogs.ADD)) {
            return Problems.forbidden("Only a developer or an admin of the project logs work");
        }
        Checked checked;
        try {
            checked = check(request);
        } catch (Wrong e) {
            return Problems.badRequest(e.getMessage());
        }
        Instant now = Instant.now();
        WorkLogEntity w = workLogs.saveAndFlush(new WorkLogEntity(issue.get().id(), user.username(),
                checked.started(now), checked.minutes(), checked.comment(), now));
        issues.logWork(issue.get().id(), w.minutes(), checked.remaining(), now);
        events.publishEvent(new WorkLogged(w.id(), issue.get().id(), issue.get().projectId(), issue.get().key(),
                user.username(), now, w.minutes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(shown.view(w, permissions)));
    }

    @ApiDoc(value = "Changes a work log: its author, or whoever manages the work logs", answer = WorkLogView.class,
            project = "odt.worklog.add")
    @PutMapping("/api/issues/{issueKey}/worklogs/{id}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String issueKey, @PathVariable UUID id, @RequestBody WorkLogRequest request) {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        WorkLogEntity w = found.get().workLog();
        if (!shown.canChange(w, found.get().permissions())) {
            return Problems.forbidden("Only its author or an admin of the project changes a work log");
        }
        Checked checked;
        try {
            checked = check(request);
        } catch (Wrong e) {
            return Problems.badRequest(e.getMessage());
        }
        if (request.version() == null || request.version() != w.version()) {
            return Problems.changedMeanwhile();
        }
        Instant now = Instant.now();
        int before = w.minutes();
        w.edit(checked.startedOr(w.startedAt()), checked.minutes(), checked.comment(), now);
        try {
            workLogs.saveAndFlush(w);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        IssueRef issue = found.get().issue();
        issues.logWork(issue.id(), w.minutes() - before, checked.remaining(), now);
        events.publishEvent(new WorkLogChanged(w.id(), issue.id(), issue.projectId(), issue.key(), user.username(), now,
                w.minutes()));
        return ResponseEntity.ok(view(shown.view(w, found.get().permissions())));
    }

    @ApiDoc(value = "Deletes a work log: its author, or whoever manages the work logs", status = 204,
            project = "odt.worklog.add")
    @DeleteMapping("/api/issues/{issueKey}/worklogs/{id}")
    @Transactional
    ResponseEntity<?> delete(@PathVariable String issueKey, @PathVariable UUID id) {
        Optional<Found> found = find(issueKey, id);
        if (found.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        WorkLogEntity w = found.get().workLog();
        if (!shown.canChange(w, found.get().permissions())) {
            return Problems.forbidden("Only its author or an admin of the project deletes a work log");
        }
        workLogs.delete(w);
        Instant now = Instant.now();
        IssueRef issue = found.get().issue();
        issues.logWork(issue.id(), -w.minutes(), null, now);
        events.publishEvent(new WorkLogDeleted(w.id(), issue.id(), issue.projectId(), issue.key(), user.username(), now,
                w.minutes()));
        return ResponseEntity.noContent().build();
    }

    private record Found(WorkLogEntity workLog, IssueRef issue, Set<String> permissions) {
    }

    private Optional<Found> find(String issueKey, UUID id) {
        return issues.find(issueKey).flatMap(issue -> {
            Set<String> permissions = permissions(issue);
            if (!permissions.contains("odt.issue.browse")) {
                return Optional.empty();
            }
            return workLogs.findById(id).filter(w -> w.issueId().equals(issue.id()))
                    .map(w -> new Found(w, issue, permissions));
        });
    }

    private Set<String> permissions(IssueRef issue) {
        return access.permissions(issue.projectId(), user.username());
    }

    private static final class Wrong extends Exception {
        Wrong(String message) {
            super(message);
        }
    }

    /** A request checked: {@code started} null when not given, {@code remaining} null to move it by the work. */
    private record Checked(int minutes, Instant started, String comment, Integer remaining) {

        Instant started(Instant now) {
            return started == null ? now.truncatedTo(ChronoUnit.MINUTES) : started;
        }

        Instant startedOr(Instant before) {
            return started == null ? before : started;
        }
    }

    private Checked check(WorkLogRequest request) throws Wrong {
        int minutes;
        Integer remaining = null;
        try {
            minutes = durations.minutes(request.timeSpent());
            if (request.remainingEstimate() != null && !request.remainingEstimate().isBlank()) {
                remaining = durations.minutesOrZero(request.remainingEstimate());
            }
        } catch (Durations.WrongDuration e) {
            throw new Wrong(e.getMessage());
        }
        Instant started = null;
        if (request.started() != null && !request.started().isBlank()) {
            String text = request.started().strip();
            try {
                started = Instant.parse(text);
            } catch (DateTimeParseException e) {
                try {
                    started = LocalDateTime.parse(text).atZone(zone).toInstant();
                } catch (DateTimeParseException again) {
                    throw new Wrong("The start is a date and time, as 2026-11-28T09:30");
                }
            }
        }
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw new Wrong("A comment is at most " + MAX_COMMENT + " characters");
        }
        return new Checked(minutes, started, comment, remaining);
    }

    private WorkLogView view(IssueWorkLogs.WorkLog w) {
        String local = LocalDateTime.ofInstant(w.started(), zone).truncatedTo(ChronoUnit.MINUTES).toString();
        return new WorkLogView(w.id(), w.author(), w.started(), local, durations.text(w.minutes()), w.minutes(),
                w.comment(), w.createdAt(), w.updatedAt(), w.version(), w.perm());
    }
}
