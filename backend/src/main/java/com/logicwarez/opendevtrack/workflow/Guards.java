/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOptions;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelLateFunctionBindings;
import dev.cel.runtime.CelRuntime.Program;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The CEL guards of the transitions (ADR-10), with the variables {@code issue} and {@code user} (maps) and the function
 * {@code can(permission)} on the project permissions of the user, as in the expressions of the screens. CEL has no I/O
 * and always ends; the macros over lists ({@code exists}, {@code all}…) are bounded by {@link #MAX_ITERATIONS}. A guard
 * that fails to evaluate (for example a field that is not there) or that is not a boolean (the fields of the maps are
 * {@code dyn}, so {@code issue.summary} compiles) is false.
 */
@Component
class Guards {

    static final int MAX_ITERATIONS = 1_000;
    private static final int MAX_LENGTH = 1_000;
    private static final String CAN = "can_string";
    private static final Logger LOG = LoggerFactory.getLogger(Guards.class);

    private final Cel cel = CelFactory.standardCelBuilder()
            .setOptions(CelOptions.current().comprehensionMaxIterations(MAX_ITERATIONS).build())
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addVar("issue", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("user", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addFunctionDeclarations(CelFunctionDecl.newFunctionDeclaration("can",
                    CelOverloadDecl.newGlobalOverload(CAN, SimpleType.BOOL, SimpleType.STRING)))
            .setResultType(SimpleType.BOOL)
            .build();

    /** Compiled once per expression; the expressions are few (those of the workflows of the projects). */
    private final Map<String, Program> programs = new ConcurrentHashMap<>();

    /** Why the expression cannot be a guard, with the position of the error; empty if it can. */
    Optional<String> check(String expression) {
        if (expression.length() > MAX_LENGTH) {
            return Optional.of("A condition is at most " + MAX_LENGTH + " characters");
        }
        CelValidationResult result = cel.compile(expression);
        return result.hasError() ? Optional.of(result.getErrorString()) : Optional.empty();
    }

    /** True for an empty guard; the values of {@code issue} and {@code user} may be null. */
    boolean allows(String expression, Map<String, ?> issue, Map<String, ?> user, Set<String> permissions) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        try {
            Object result = programs.computeIfAbsent(expression, this::program).eval(
                    Map.of("issue", issue, "user", user),
                    CelLateFunctionBindings.from(CelFunctionBinding.from(CAN, String.class, permissions::contains)));
            return Boolean.TRUE.equals(result);
        } catch (CelEvaluationException e) {
            LOG.debug("Guard {} false: {}", expression, e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            LOG.warn(e.getMessage());
            return false;
        }
    }

    private Program program(String expression) {
        try {
            return cel.createProgram(cel.compile(expression).getAst());
        } catch (CelValidationException | CelEvaluationException e) {
            // Saved guards are checked first: this is a guard written in the database by hand.
            throw new IllegalStateException("Invalid guard " + expression + ": " + e.getMessage(), e);
        }
    }
}
