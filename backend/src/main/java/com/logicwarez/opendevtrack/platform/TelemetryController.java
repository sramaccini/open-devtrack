/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The telemetry of the Quadrava runtime ({@code contracts/telemetry.yaml} 0.1.0): the traces and the logs of the
 * screens in OTLP/HTTP JSON, which the runtime posts to {@code /telemetry/v1} and its nginx forwards here. Signed in
 * like the rest (a session or a token), within the limit of requests of a user (429). A body over the limit gets 413,
 * one that is not the OTLP payload of its signal 400, each as a Problem; the rest 202.
 *
 * <p>An accepted payload has a second redaction (the runtime does the first, on the fields marked sensitive): IBANs,
 * Italian tax codes, e-mail addresses and JWTs become {@value #MASK}. Then it goes to the OpenTelemetry collector of
 * the installation, {@code odt.telemetry.otlp-endpoint} ({@code /v1/traces}, {@code /v1/logs}), in the background and
 * at most {@code odt.telemetry.max-in-flight} at a time; without a collector, or when too many are on their way, it is
 * dropped. The counter {@code odt.telemetry.payloads} tells by signal and outcome.
 */
@RestController
class TelemetryController {

    static final String MASK = "[REDACTED]";

    private static final Logger log = LoggerFactory.getLogger(TelemetryController.class);
    private static final List<Pattern> PERSONAL = List.of(
            // IBAN
            Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"),
            // Italian tax code
            Pattern.compile("\\b[A-Z]{6}\\d{2}[A-Z]\\d{2}[A-Z]\\d{3}[A-Z]\\b"),
            // e-mail address
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            // JWT (a token in a URL or a message)
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final MeterRegistry metrics;
    private final int maxBytes;
    private final URI collector;
    private final Semaphore inFlight;

    TelemetryController(MeterRegistry metrics, @Value("${odt.telemetry.max-bytes:1000000}") int maxBytes,
                        @Value("${odt.telemetry.otlp-endpoint:}") String collector,
                        @Value("${odt.telemetry.max-in-flight:16}") int maxInFlight) {
        this.metrics = metrics;
        this.maxBytes = maxBytes;
        this.collector = collector.isBlank() ? null : URI.create(collector.replaceAll("/+$", ""));
        this.inFlight = new Semaphore(maxInFlight);
    }

    @PostMapping(path = "/telemetry/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> traces(HttpServletRequest request) throws IOException {
        return accept("traces", "resourceSpans", request);
    }

    @PostMapping(path = "/telemetry/v1/logs", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> logs(HttpServletRequest request) throws IOException {
        return accept("logs", "resourceLogs", request);
    }

    private ResponseEntity<?> accept(String signal, String list, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBytes) {
            return tooLarge();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            // At most one byte over the limit: a body without its length is not read whole either
            body = in.readNBytes(maxBytes + 1);
        }
        if (body.length > maxBytes) {
            return tooLarge();
        }
        JsonNode document;
        try {
            document = json.readTree(body);
        } catch (IOException e) {
            return Problems.badRequest("The body is not a JSON object");
        }
        if (document == null || !document.isObject()) {
            return Problems.badRequest("The body is not a JSON object");
        }
        if (!document.path(list).isArray()) {
            return Problems.badRequest("The " + list + " list is missing (OTLP/HTTP JSON payload of " + signal + ")");
        }
        forward(signal, redact(new String(body, StandardCharsets.UTF_8)));
        return ResponseEntity.accepted().build();
    }

    /** The payload without the personal data that can be recognised in it. */
    static String redact(String payload) {
        String clean = payload;
        for (Pattern pattern : PERSONAL) {
            clean = pattern.matcher(clean).replaceAll(MASK);
        }
        return clean;
    }

    private void forward(String signal, String payload) {
        if (collector == null) {
            count(signal, "dropped");
            return;
        }
        if (!inFlight.tryAcquire()) {
            count(signal, "dropped");
            return;
        }
        HttpRequest post = HttpRequest.newBuilder(URI.create(collector + "/v1/" + signal))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        http.sendAsync(post, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            inFlight.release();
            if (error == null && response.statusCode() / 100 == 2) {
                count(signal, "forwarded");
            } else {
                count(signal, "failed");
                log.warn("Telemetry not forwarded to the collector: {}",
                        error != null ? error.toString() : "status " + response.statusCode());
            }
        });
    }

    private void count(String signal, String outcome) {
        metrics.counter("odt.telemetry.payloads", "signal", signal, "outcome", outcome).increment();
    }

    private ResponseEntity<ProblemDetail> tooLarge() {
        return Problems.of(HttpStatus.CONTENT_TOO_LARGE, "Body over the limit of " + maxBytes + " bytes");
    }
}
