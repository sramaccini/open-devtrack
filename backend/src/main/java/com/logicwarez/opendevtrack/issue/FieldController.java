/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.FieldDefinitionEntity.Option;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import com.logicwarez.opendevtrack.project.ProjectRef;
import com.logicwarez.opendevtrack.project.Projects;
import com.logicwarez.opendevtrack.shared.Conditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The custom fields of a project (ADR-11). The members read them, as the rows of {@code fieldsFrom} of a new issue;
 * the admins of the project ({@code odt.project.edit}) define them, change their label, options and
 * {@code required_if}, and archive or restore them. Key and type do not change once a field exists. The options are
 * written one per line, {@code value} or {@code value: Label}.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class FieldController {

    static final int MAX_FIELDS = 50;
    private static final int MAX_OPTIONS = 100;
    private static final int MAX_LABEL = 100;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Pattern OPTION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$");
    private static final String NOT_FOUND = "Project not found";
    private static final String NOT_AN_ADMIN = "Only an admin of the project changes its fields";

    private final Projects projects;
    private final FieldDefinitionRepository definitions;
    private final CustomFields customFields;
    private final ProjectAccess access;
    private final CurrentUser user;
    private final Conditions conditions;

    FieldController(Projects projects, FieldDefinitionRepository definitions, CustomFields customFields,
                    ProjectAccess access, CurrentUser user, Conditions conditions) {
        this.projects = projects;
        this.definitions = definitions;
        this.customFields = customFields;
        this.access = access;
        this.user = user;
        this.conditions = conditions;
    }

    /** A field as its admins see it, the options as text to edit. */
    record DefinitionView(String key, String label, String type, String options, String requiredIf, boolean archived,
                          long version, @JsonProperty("_perm") Map<String, Boolean> perm) {
    }

    /** {@code key} and {@code type} only when the field is created; {@code version} only when it changes. */
    record DefinitionRequest(String key, String label, String type, String options, String requiredIf,
                             Boolean archived, Long version) {
    }

    /** The rows of {@code fieldsFrom} for a new issue of the project. */
    @GetMapping("/api/projects/{key}/fields")
    ResponseEntity<?> rows(@PathVariable String key) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(project.get()).contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its fields");
        }
        return ResponseEntity.ok(customFields.rows(project.get().id(), null, Map.of(), Set.of()));
    }

    @GetMapping("/api/projects/{key}/field-definitions")
    ResponseEntity<?> list(@PathVariable String key) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        Set<String> permissions = permissions(project.get());
        if (!permissions.contains("odt.issue.browse")) {
            return Problems.forbidden("Only the members of the project see its fields");
        }
        boolean edit = permissions.contains("odt.project.edit");
        return ResponseEntity.ok(definitions.findByProjectIdOrderByPositionAscKeyAsc(project.get().id()).stream()
                .map(d -> view(d, edit)).toList());
    }

    @PostMapping("/api/projects/{key}/field-definitions")
    @Transactional
    ResponseEntity<?> create(@PathVariable String key, @RequestBody DefinitionRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(project.get()).contains("odt.project.edit")) {
            return Problems.forbidden(NOT_AN_ADMIN);
        }
        String fieldKey = request.key() == null ? "" : request.key().strip();
        if (!KEY.matcher(fieldKey).matches()) {
            return Problems.badRequest("The key is 1 to 40 lower-case letters, digits or underscores, from a letter");
        }
        Optional<FieldType> type = FieldType.parse(request.type());
        if (type.isEmpty()) {
            return Problems.badRequest("The type is one of " + String.join(", ", FieldType.names()));
        }
        if (definitions.findByProjectIdAndKey(project.get().id(), fieldKey).isPresent()) {
            return Problems.conflict("The project already has a field with this key");
        }
        long count = definitions.countByProjectId(project.get().id());
        if (count >= MAX_FIELDS) {
            return Problems.badRequest("At most " + MAX_FIELDS + " fields per project");
        }
        FieldDefinitionEntity field = new FieldDefinitionEntity(project.get().id(), fieldKey, type.get(), (int) count);
        try {
            apply(field, request);
        } catch (InvalidDefinition e) {
            return Problems.badRequest(e.getMessage());
        }
        definitions.saveAndFlush(field);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(field, true));
    }

    /** Label, options, {@code required_if} and archived, from the version read, or 412 if it changed meanwhile. */
    @PutMapping("/api/projects/{key}/field-definitions/{fieldKey}")
    @Transactional
    ResponseEntity<?> edit(@PathVariable String key, @PathVariable String fieldKey,
                           @RequestBody DefinitionRequest request) {
        Optional<ProjectRef> project = projects.find(key);
        if (project.isEmpty()) {
            return Problems.notFound(NOT_FOUND);
        }
        if (!permissions(project.get()).contains("odt.project.edit")) {
            return Problems.forbidden(NOT_AN_ADMIN);
        }
        Optional<FieldDefinitionEntity> found = definitions.findByProjectIdAndKey(project.get().id(), fieldKey);
        if (found.isEmpty()) {
            return Problems.notFound("Field not found");
        }
        FieldDefinitionEntity field = found.get();
        if (request.version() == null || request.version() != field.version()) {
            return Problems.changedMeanwhile();
        }
        try {
            apply(field, request);
        } catch (InvalidDefinition e) {
            return Problems.badRequest(e.getMessage());
        }
        try {
            definitions.saveAndFlush(field);
        } catch (ObjectOptimisticLockingFailureException e) {
            return Problems.changedMeanwhile();
        }
        return ResponseEntity.ok(view(field, true));
    }

    private static final class InvalidDefinition extends Exception {
        InvalidDefinition(String message) {
            super(message);
        }
    }

    private void apply(FieldDefinitionEntity field, DefinitionRequest request) throws InvalidDefinition {
        String label = request.label() == null ? "" : request.label().strip();
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            throw new InvalidDefinition("The label is required, at most " + MAX_LABEL + " characters");
        }
        List<Option> options = options(request.options());
        if (field.type().hasOptions() && options.isEmpty()) {
            throw new InvalidDefinition("A " + field.type().value() + " field needs at least one option");
        }
        String requiredIf = request.requiredIf() == null || request.requiredIf().isBlank() ? null
                : request.requiredIf().strip();
        if (requiredIf != null) {
            Optional<String> invalid = conditions.check(requiredIf);
            if (invalid.isPresent()) {
                throw new InvalidDefinition("The rule is not valid CEL: " + invalid.get());
            }
        }
        field.edit(label, field.type().hasOptions() ? options : List.of(), requiredIf,
                Boolean.TRUE.equals(request.archived()));
    }

    /** One option per line: {@code value} or {@code value: Label}. */
    private static List<Option> options(String text) throws InvalidDefinition {
        List<Option> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (text == null) {
            return options;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            String value = (colon < 0 ? line : line.substring(0, colon)).strip();
            String label = colon < 0 ? value : line.substring(colon + 1).strip();
            if (!OPTION.matcher(value).matches()) {
                throw new InvalidDefinition("An option is 1 to 50 letters, digits, dots, dashes or underscores, "
                        + "then an optional label after a colon");
            }
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                throw new InvalidDefinition("The label of an option is at most " + MAX_LABEL + " characters");
            }
            if (seen.add(value)) {
                options.add(new Option(value, label));
            }
        }
        if (options.size() > MAX_OPTIONS) {
            throw new InvalidDefinition("At most " + MAX_OPTIONS + " options");
        }
        return options;
    }

    private Set<String> permissions(ProjectRef project) {
        return access.permissions(project.id(), user.username());
    }

    private static DefinitionView view(FieldDefinitionEntity d, boolean edit) {
        String options = d.options().stream()
                .map(o -> o.value().equals(o.label()) ? o.value() : o.value() + ": " + o.label())
                .collect(Collectors.joining("\n"));
        return new DefinitionView(d.key(), d.label(), d.type().value(), options, d.requiredIf(), d.archived(),
                d.version(), Map.of("edit", edit));
    }
}
