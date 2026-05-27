package dev.vatn.plugins.s3;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;

/**
 * S3 object-storage plugin — registers an {@link S3Service} backed by the AWS SDK v2.
 *
 * <p>Supports AWS S3 and any S3-compatible endpoint (MinIO, Cloudflare R2, DigitalOcean Spaces).
 *
 * <pre>{@code
 * // AWS
 * node.use(new S3Plugin(S3Config.aws("eu-west-1", "my-bucket", accessKey, secretKey)));
 *
 * // MinIO / R2 / Spaces
 * node.use(new S3Plugin(S3Config.compatible("http://localhost:9000",
 *         "us-east-1", "my-bucket", accessKey, secretKey)));
 * }</pre>
 */
public class S3Plugin implements VNodePlugin {

    private final S3Config config;
    private S3ServiceImpl service;

    public S3Plugin(S3Config config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.s3"; }
    @Override public String getName()    { return "VATN S3 Plugin"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        service = new S3ServiceImpl(config);
        ctx.registerService(S3Service.class, service);
    }

    @Override
    public void onShutdown() {
        if (service != null) service.close();
    }
}
