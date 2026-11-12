/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What a path of {@code /api} does, for its OpenAPI definition (ADR-21, {@code docs/openapi.json}): the paths answer
 * {@code ResponseEntity<?>} (a body or a Problem), so springdoc cannot see the body of the success answer by itself.
 * Read by {@link OpenApiConfig}; the global permission comes from {@code @PreAuthorize}.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiDoc {

    /** One line on what the path does. */
    String value();

    /** The body of the success answer; {@link Void} for none. */
    Class<?> answer() default Void.class;

    /** The answer is a list of {@link #answer()}. */
    boolean list() default false;

    /** The status of the success answer: 200, 201 for what is created, 204 without a body. */
    int status() default 200;

    /** The permissions the path checks in the role of the user in the project of the request (ADR-07). */
    String[] project() default {};

    /** Serves only a screen ({@code x-internal}): it works the same, but may change without notice. */
    boolean internal() default false;
}
