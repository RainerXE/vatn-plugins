package dev.vatn.plugins.node;

import dev.vatn.api.VNodeContext;
import dev.vatn.api.VNodePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VATN Node.js Runtime Plugin — sandboxed Node.js process manager.
 *
 * <p>Drop in and Node.js is available to any plugin via {@link NodeRuntime}:
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new NodePlugin())
 *     .addPlugin(new MyPlugin())
 *     .start();
 *
 * // In MyPlugin.onInitialize():
 * NodeRuntime node = ctx.getService(NodeRuntime.class).orElseThrow();
 * NodeProcessManager procs = ctx.getService(NodeProcessManager.class).orElseThrow();
 *
 * // Run a vatn-node.json script:
 * var script = NodeScriptRunner.NodeScript.fromFile(appDir.resolve("vatn-node.json"));
 * new NodeScriptRunner(node, procs, "myapp", appDir).run(script);
 * }</pre>
 *
 * <p>Script format is the same {@code run[]} array as vatn-plugin-python, making
 * the two plugins feel consistent. Apps live in {@code .vatn/node/apps/}.
 *
 * <p>Admin UI: {@code GET /node/ui}
 */
public class NodePlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(NodePlugin.class);

    private final NodeConfig     config;
    private NodeRuntime          runtime;
    private NodeProcessManager   manager;

    public NodePlugin() {
        this(NodeConfig.builder().build());
    }

    public NodePlugin(NodeConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.node"; }
    @Override public String getName()    { return "VATN Node.js Runtime"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        log.info("Initializing VATN Node.js Runtime Plugin");
        try {
            runtime = new NodeRuntime(config, ctx.getWorkspacePath());
            runtime.initialize();
            manager = new NodeProcessManager(config);

            ctx.registerService(NodeRuntime.class,        runtime);
            ctx.registerService(NodeProcessManager.class, manager);
            ctx.register("/node", new NodeHttpService(runtime, manager));
            ctx.registerHealthCheck("node", () -> runtime.isHealthy());

            log.info("Node.js plugin ready — {} | npm={} | apps={} | UI: /node/ui",
                runtime.nodeVersion(), runtime.npmVersion(), runtime.appNames().size());
        } catch (Exception e) {
            log.error("Failed to initialize Node.js plugin: {}", e.getMessage(), e);
            throw new RuntimeException("Node.js plugin initialization failed", e);
        }
    }

    @Override
    public void onShutdown() {
        log.info("Node.js plugin shutting down — stopping all processes");
        if (manager != null) manager.stopAll();
    }
}
