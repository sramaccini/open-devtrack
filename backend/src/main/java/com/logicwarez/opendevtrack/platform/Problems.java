/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/** Error answers of the API in the Problem format (RFC 9457), with English titles. */
public final class Problems {

    private Problems() {
    }

    public static ResponseEntity<ProblemDetail> of(HttpStatus status, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** 404: also what another tenant gets for a project that exists (ADR-09), so it learns nothing. */
    public static ResponseEntity<ProblemDetail> notFound(String title) {
        return of(HttpStatus.NOT_FOUND, title);
    }

    public static ResponseEntity<ProblemDetail> badRequest(String title) {
        return of(HttpStatus.BAD_REQUEST, title);
    }

    public static ResponseEntity<ProblemDetail> forbidden(String title) {
        return of(HttpStatus.FORBIDDEN, title);
    }

    public static ResponseEntity<ProblemDetail> conflict(String title) {
        return of(HttpStatus.CONFLICT, title);
    }

    /** 412: someone changed the record after it was read (optimistic locking with its version). */
    public static ResponseEntity<ProblemDetail> changedMeanwhile() {
        return of(HttpStatus.PRECONDITION_FAILED, "Someone else changed it in the meantime: reload and try again");
    }
}
