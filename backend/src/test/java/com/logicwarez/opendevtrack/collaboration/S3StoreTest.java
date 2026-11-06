/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The attachments on the store s3 (ADR-14), against S3Mock: an S3-compatible server reached through {@code endpoint}
 * and {@code path-style}, as MinIO, Ceph or Garage would be, with a prefix in a bucket created before the start.
 */
class S3StoreTest extends AttachmentTestBase {

    static final String BUCKET = "opendevtrack";
    static final String PREFIX = "attachments/";
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/favicon.ico").forPort(9090).forStatusCodeMatching(c -> c < 500));

    static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    static S3Client client() {
        return S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("odt", "odt-secret")))
                .build();
    }

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) {
        S3.start();
        try (S3Client s3 = client()) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        registry.add("odt.attachments.store", () -> "s3");
        registry.add("odt.attachments.s3.bucket", () -> BUCKET);
        registry.add("odt.attachments.s3.prefix", () -> PREFIX);
        registry.add("odt.attachments.s3.endpoint", S3StoreTest::endpoint);
        registry.add("odt.attachments.s3.path-style", () -> "true");
        registry.add("odt.attachments.s3.access-key", () -> "odt");
        registry.add("odt.attachments.s3.secret-key", () -> "odt-secret");
    }

    @Override
    String expectedStore() {
        return "s3";
    }

    @Test
    void anObjectIsNamedByThePrefixAndItsKey() throws Exception {
        String key = key(upload(luisa, "notes.txt", "text/plain", "notes".getBytes(StandardCharsets.UTF_8)));
        try (S3Client s3 = client()) {
            assertThat(s3.listObjectsV2(b -> b.bucket(BUCKET)).contents())
                    .extracting(o -> o.key())
                    .contains(PREFIX + key)
                    .noneMatch(k -> k.contains("notes"));
        }
    }
}
