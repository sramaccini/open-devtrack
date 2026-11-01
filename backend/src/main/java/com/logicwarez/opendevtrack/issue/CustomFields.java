/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.access.ProjectAccess;
import com.logicwarez.opendevtrack.issue.FieldDefinitionEntity.Option;
import com.logicwarez.opendevtrack.platform.Problems.FieldError;
import com.logicwarez.opendevtrack.shared.Conditions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The values of the custom fields of an issue (ADR-11), checked against the definitions of its project: their type,
 * their options and, once the issue is put together, their {@code required_if}. The values of an archived field are
 * kept as they were, whatever the request says; a key that no field of the project has is refused.
 */
@Component
class CustomFields {

    static final int MAX_TEXT = 1_000;
    static final String INVALID = "customFieldInvalid";
    static final String UNKNOWN = "customFieldUnknown";
    static final String TOO_LONG = "customFieldTooLong";
    static final String REQUIRED = "customFieldRequired";
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");

    private final FieldDefinitionRepository definitions;
    private final ProjectAccess access;
    private final Conditions conditions;

    CustomFields(FieldDefinitionRepository definitions, ProjectAccess access, Conditions conditions) {
        this.definitions = definitions;
        this.access = access;
        this.conditions = conditions;
    }

    /** A row of {@code fieldsFrom}: the field as the form draws it. */
    record Row(String key, String label, String type, List<Option> options, boolean required) {
    }

    /** The values given, checked; {@code errors} empty when they can be saved. */
    record Checked(Map<String, Object> values, List<FieldError> errors) {
    }

    List<FieldDefinitionEntity> all(UUID projectId) {
        return definitions.findByProjectIdOrderByPositionAscKeyAsc(projectId);
    }

    /**
     * The fields of the forms, without the archived ones; {@code issue} the issue as the conditions see it, or null for
     * a new issue, where only the fields always required ({@code true}) are marked.
     */
    List<Row> rows(UUID projectId, Map<String, ?> issue, Map<String, ?> user, Set<String> permissions) {
        return all(projectId).stream().filter(d -> !d.archived())
                .map(d -> new Row(d.key(), d.label(), d.type().value(), d.options(),
                        issue == null ? "true".equals(d.requiredIf()) : required(d, issue, user, permissions)))
                .toList();
    }

    /** The values of {@code given} on top of those {@code before}: archived fields keep theirs, empty values go. */
    Checked check(UUID projectId, Map<String, ?> given, Map<String, ?> before) {
        Map<String, FieldDefinitionEntity> byKey = all(projectId).stream()
                .collect(Collectors.toMap(FieldDefinitionEntity::key, d -> d));
        Map<String, Object> values = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();
        before.forEach((key, value) -> {
            FieldDefinitionEntity d = byKey.get(key);
            if (d == null || d.archived()) {
                values.put(key, value);
            }
        });
        given.forEach((key, value) -> {
            FieldDefinitionEntity d = byKey.get(key);
            if (d == null) {
                errors.add(error(key, UNKNOWN));
            } else if (!d.archived()) {
                try {
                    Object checked = value(projectId, d, value);
                    if (checked != null) {
                        values.put(key, checked);
                    }
                } catch (InvalidValue e) {
                    errors.add(error(key, e.code));
                }
            }
        });
        return new Checked(values, errors);
    }

    /** The fields required for the issue as it would be saved, and without a value. */
    List<FieldError> missing(UUID projectId, Map<String, ?> issue, Map<String, ?> user, Set<String> permissions) {
        Object custom = issue.get("custom");
        Map<?, ?> values = custom instanceof Map<?, ?> m ? m : Map.of();
        return all(projectId).stream()
                .filter(d -> !d.archived() && empty(values.get(d.key())) && required(d, issue, user, permissions))
                .map(d -> error(d.key(), REQUIRED))
                .toList();
    }

    /** A value as text, for the changelog: lists joined with commas. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    private boolean required(FieldDefinitionEntity d, Map<String, ?> issue, Map<String, ?> user,
                             Set<String> permissions) {
        return d.requiredIf() != null && !d.requiredIf().isBlank()
                && conditions.allows(d.requiredIf(), issue, user, permissions);
    }

    private static boolean empty(Object value) {
        return value == null || Boolean.FALSE.equals(value) || value instanceof List<?> l && l.isEmpty();
    }

    private static FieldError error(String key, String code) {
        return new FieldError("/custom/" + key, code);
    }

    private static final class InvalidValue extends Exception {
        private final String code;

        InvalidValue(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    /** The value to save, or null for none. */
    private Object value(UUID projectId, FieldDefinitionEntity d, Object value) throws InvalidValue {
        if (value == null || value instanceof String s && s.isBlank()) {
            return null;
        }
        Set<String> options = d.options().stream().map(Option::value).collect(Collectors.toSet());
        return switch (d.type()) {
            case TEXT -> {
                String text = string(value).strip();
                if (text.length() > MAX_TEXT) {
                    throw new InvalidValue(TOO_LONG);
                }
                yield text;
            }
            case NUMBER -> {
                try {
                    yield number(new BigDecimal(value instanceof Number n ? n.toString() : string(value).strip()));
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new InvalidValue(INVALID);
                }
            }
            case DATE -> {
                String date = string(value).strip();
                if (!DATE.matcher(date).find()) {
                    throw new InvalidValue(INVALID);
                }
                try {
                    yield LocalDate.parse(date.substring(0, 10)).toString();
                } catch (DateTimeParseException e) {
                    throw new InvalidValue(INVALID);
                }
            }
            case CHECKBOX -> {
                if (!(value instanceof Boolean b)) {
                    throw new InvalidValue(INVALID);
                }
                yield b;
            }
            case SELECT -> {
                String option = string(value);
                if (!options.contains(option)) {
                    throw new InvalidValue(INVALID);
                }
                yield option;
            }
            case MULTISELECT -> {
                if (!(value instanceof List<?> list)) {
                    throw new InvalidValue(INVALID);
                }
                Set<String> chosen = new LinkedHashSet<>();
                for (Object o : list) {
                    if (!(o instanceof String s) || !options.contains(s)) {
                        throw new InvalidValue(INVALID);
                    }
                    chosen.add(s);
                }
                yield chosen.isEmpty() ? null : new ArrayList<>(chosen);
            }
            case USER -> {
                String username = string(value).strip();
                if (access.role(projectId, username).isEmpty()) {
                    throw new InvalidValue(INVALID);
                }
                yield username;
            }
        };
    }

    /**
     * A number as JSON reads it back (an integer, a long or a double): the issue keeps comparing equal to its copy read
     * from the database, so saving it once does not look like a second change.
     */
    private static Number number(BigDecimal n) throws InvalidValue {
        BigDecimal plain = n.stripTrailingZeros();
        if (plain.scale() <= 0 && plain.toBigInteger().bitLength() < 32) {
            return plain.intValueExact();
        }
        if (plain.scale() <= 0 && plain.toBigInteger().bitLength() < 64) {
            return plain.longValueExact();
        }
        double d = plain.doubleValue();
        if (Double.isInfinite(d)) {
            throw new InvalidValue(INVALID);
        }
        return d;
    }

    private static String string(Object value) throws InvalidValue {
        if (!(value instanceof String s)) {
            throw new InvalidValue(INVALID);
        }
        return s;
    }
}
