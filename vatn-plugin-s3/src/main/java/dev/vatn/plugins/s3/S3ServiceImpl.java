package dev.vatn.plugins.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

final class S3ServiceImpl implements S3Service {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    S3ServiceImpl(S3Config config) {
        this.bucket = config.getBucket();

        AwsBasicCredentials creds = AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey());
        Region region = Region.of(config.getRegion());

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        if (config.isCustomEndpoint()) {
            URI endpoint = URI.create(config.getEndpoint());
            builder.endpointOverride(endpoint).forcePathStyle(true);
            presignerBuilder.endpointOverride(endpoint);
        }

        this.client   = builder.build();
        this.presigner = presignerBuilder.build();
    }

    void close() {
        client.close();
        presigner.close();
    }

    @Override
    public void upload(String key, byte[] data, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public void upload(String key, InputStream stream, long contentLength, String contentType) {
        client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key)
                        .contentType(contentType).contentLength(contentLength).build(),
                RequestBody.fromInputStream(stream, contentLength));
    }

    @Override
    public byte[] download(String key) {
        return client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public InputStream downloadStream(String key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public List<String> list(String prefix) {
        ListObjectsV2Response response = client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
        return response.contents().stream()
                .map(S3Object::key)
                .collect(Collectors.toList());
    }

    @Override
    public String presignUrl(String key, Duration validity) {
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(validity)
                        .getObjectRequest(r -> r.bucket(bucket).key(key))
                        .build());
        return presigned.url().toString();
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
