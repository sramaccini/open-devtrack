/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration.storage.s3;

import com.logicwarez.opendevtrack.collaboration.storage.AttachmentStore;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The store {@code s3} (ADR-14): an object per attachment in {@code odt.attachments.s3.bucket}, named
 * {@code <prefix><project id>/<attachment id>}. Any S3-compatible server works (AWS S3, MinIO, Ceph, Garage):
 * {@code endpoint} points to it and {@code path-style} names the bucket in the path instead of the host. Without
 * {@code access-key} and {@code secret-key} the credentials come from the usual chain of the AWS SDK (environment,
 * profile, role of the instance).
 */
@Component
@ConditionalOnProperty(name = "odt.attachments.store", havingValue = S3Store.NAME)
class S3Store implements AttachmentStore {

    static final String NAME = "s3";
    private static final String PROBE = "check/probe";

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    S3Store(@Value("${odt.attachments.s3.bucket}") String bucket,
            @Value("${odt.attachments.s3.prefix:}") String prefix,
            @Value("${odt.attachments.s3.endpoint:}") String endpoint,
            @Value("${odt.attachments.s3.region:us-east-1}") String region,
            @Value("${odt.attachments.s3.path-style:false}") boolean pathStyle,
            @Value("${odt.attachments.s3.access-key:}") String accessKey,
            @Value("${odt.attachments.s3.secret-key:}") String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(region))
                .forcePathStyle(pathStyle);
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        builder.credentialsProvider(accessKey.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        this.s3 = builder.build();
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @PreDestroy
    void close() {
        s3.close();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void put(String key, InputStream content, long size) throws IOException {
        try {
            s3.putObject(b -> b.bucket(bucket).key(prefix + key).contentLength(size),
                    RequestBody.fromInputStream(content, size));
        } catch (SdkException e) {
            throw new IOException("The store s3 did not keep " + key, e);
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        try {
            return s3.getObject(b -> b.bucket(bucket).key(prefix + key));
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(key);
        } catch (SdkException e) {
            throw new IOException("The store s3 did not give back " + key, e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            s3.deleteObject(b -> b.bucket(bucket).key(prefix + key));
        } catch (SdkException e) {
            throw new IOException("The store s3 did not remove " + key, e);
        }
    }

    @Override
    public void keys(Instant before, Consumer<String> key) throws IOException {
        try {
            s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                    .filter(o -> o.lastModified().isBefore(before))
                    .forEach(o -> key.accept(o.key().substring(prefix.length())));
        } catch (SdkException e) {
            throw new IOException("The store s3 did not list the bucket " + bucket, e);
        }
    }

    @Override
    public void check() throws IOException {
        byte[] probe = "odt".getBytes(StandardCharsets.US_ASCII);
        put(PROBE, new ByteArrayInputStream(probe), probe.length);
        try (InputStream back = get(PROBE)) {
            if (!Arrays.equals(back.readAllBytes(), probe)) {
                throw new IOException("The store s3 gave back other bytes than those written in " + bucket);
            }
        } finally {
            delete(PROBE);
        }
    }
}
