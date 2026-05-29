package dev.vatn.plugins.python;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VATN Python Runtime Plugin — Pinokio-compatible sandboxed Python execution.
 *
 * <p>Drop in and Python is available to any plugin via the {@link PythonRuntime}
 * service:
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new PythonPlugin())       // default config
 *     .addPlugin(new MyPlugin())
 *     .start();
 *
 * // In MyPlugin.onInitialize():
 * PythonRuntime python = ctx.getService(PythonRuntime.class).orElseThrow();
 * python.createEnv("myapp");
 *
 * // Or run a Pinokio script directly:
 * PythonProcessManager procs = ctx.getService(PythonProcessManager.class).orElseThrow();
 * var runner = new PynokioScriptRunner(python, procs, "myapp", appDir);
 * runner.run(PynokioScript.fromFile(appDir.resolve("pinokio.json")));
 * }</pre>
 *
 * <p>Admin UI: {@code GET /python/ui}
 */
public class PythonPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(PythonPlugin.class);

    private final PythonConfig     config;
    private PythonRuntime          runtime;
    private PythonProcessManager   manager;

    public PythonPlugin() {
        this(PythonConfig.builder().build());
    }

    public PythonPlugin(PythonConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.python"; }
    @Override public String getName()    { return "VATN Python Runtime"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN Python Runtime Plugin");
        try {
            runtime = new PythonRuntime(config, ctx.getWorkspacePath());
            runtime.initialize();
            manager = new PythonProcessManager(config);

            ctx.registerService(PythonRuntime.class,        runtime);
            ctx.registerService(PythonProcessManager.class, manager);
            ctx.register("/python", new PythonHttpService(runtime, manager));
            ctx.registerHealthCheck("python", () -> runtime.isHealthy());

            log.info("Python plugin ready — {} | uv={} | envs={} | UI: /python/ui",
                runtime.pythonVersion(), runtime.uvAvailable(), runtime.envNames().size());
        } catch (Exception e) {
            log.error("Failed to initialize Python plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Python plugin initialization failed", e);
        }
    }

    @Override
    public void onShutdown() {
        log.info("Python plugin shutting down — stopping all processes");
        if (manager != null) manager.stopAll();
    }
}
