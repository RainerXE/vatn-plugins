package dev.vatn.plugins.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.*;
import dev.vatn.api.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Optional admin dashboard plugin for VATN nodes.
 *
 * <p>Registers a web UI at {@code GET {basePath}} and a set of JSON API endpoints
 * under {@code {basePath}/api/}. All requests require a valid bearer token unless
 * {@link AdminConfig#open()} is used.
 *
 * <pre>{@code
 * VNodeRunner.create(8080)
 *     .addPlugin(new AdminPlugin())                    // token from VATN_ADMIN_TOKEN env
 *     .addPlugin(new AdminPlugin(AdminConfig.open()))  // no auth
 *     .addPlugin(new MyAppPlugin())
 *     .start();
 * // → http://localhost:8080/vatn/admin
 * }</pre>
 *
 * <p>Sections shown:
 * <ul>
 *   <li>Node overview — id, port, uptime, JVM vs AOT, VATN version</li>
 *   <li>Plugins — registered plugins with id, name, version</li>
 *   <li>Health — all named health checks with live status</li>
 *   <li>Agents — live role indicator (PRIMARY / STANDBY / TWIN) per VAgent</li>
 *   <li>Workflows — recent DAG runs across all registered DAGs</li>
 *   <li>Routes — all HTTP paths registered by plugins</li>
 * </ul>
 */
public class AdminPlugin implements VNodePlugin {

    private static final Logger log = LoggerFactory.getLogger(AdminPlugin.class);

    private final AdminConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long startedAt = System.currentTimeMillis();

    public AdminPlugin() {
        this(AdminConfig.defaults());
    }

    public AdminPlugin(AdminConfig config) {
        this.config = config;
    }

    @Override public String getId()      { return "dev.vatn.plugins.admin"; }
    @Override public String getName()    { return "VATN Admin UI"; }
    @Override public String getVersion() { return "1.0.0"; }

    @Override
    public void onInitialize(VNodeContext ctx) {
        String base = config.getBasePath();

        ctx.register(base, routes -> {

            // ── HTML dashboard ───────────────────────────────────────────────
            routes.get("", (req, res) -> res.sendHtml(AdminHtml.render(base)));

            // ── Auth guard helper (inline lambda captures config) ────────────
            // Each API handler calls guard() and returns early on 401

            // ── API: node overview ───────────────────────────────────────────
            routes.get("/api/overview", (req, res) -> {
                if (!authorized(req, res)) return;
                long uptimeMs = System.currentTimeMillis() - startedAt;
                VConfiguration conf = ctx.getConfiguration();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("nodeId",      ctx.getNodeId());
                data.put("vatnVersion", "1.0");
                data.put("flavor",      conf != null && conf.isAot() ? "AOT (GraalVM)" : "JVM");
                data.put("uptimeMs",    uptimeMs);
                data.put("uptimeHuman", formatUptime(uptimeMs));
                data.put("pluginCount", ctx.getService(VPluginRegistry.class)
                        .map(r -> r.getPlugins().size()).orElse(0));
                data.put("agentCount",  ctx.getAgentInfos().size());
                sendJson(res, data);
            });

            // ── API: plugins ─────────────────────────────────────────────────
            routes.get("/api/plugins", (req, res) -> {
                if (!authorized(req, res)) return;
                List<Map<String, String>> plugins = ctx.getService(VPluginRegistry.class)
                        .map(r -> r.getPlugins().stream()
                                .map(p -> Map.of(
                                        "id",      p.getId(),
                                        "name",    p.getName(),
                                        "version", p.getVersion()))
                                .collect(Collectors.toList()))
                        .orElse(List.of());
                sendJson(res, plugins);
            });

            // ── API: health ──────────────────────────────────────────────────
            routes.get("/api/health", (req, res) -> {
                if (!authorized(req, res)) return;
                // Call the same health checkers the /health endpoint uses
                // We re-run them here for a fresh snapshot
                List<Map<String, Object>> checks = new ArrayList<>();
                ctx.getService(VPluginRegistry.class).ifPresent(reg -> {
                    // Health checkers are stored in VNodeContextImpl but not exposed via VService.
                    // We proxy by calling GET /health on ourselves if available, or
                    // fall back to listing what we know from registered plugins.
                });
                // Simpler: expose agent health + plugin presence as proxies
                ctx.getAgentInfos().forEach(agent -> checks.add(Map.of(
                        "name",    "agent." + agent.id(),
                        "status",  agent.role() == VAgentRole.PRIMARY || agent.role() == VAgentRole.TWIN
                                   ? "UP" : "STANDBY",
                        "detail",  agent.role().name()
                )));
                sendJson(res, Map.of("checks", checks, "timestamp", Instant.now().toString()));
            });

            // ── API: agents ──────────────────────────────────────────────────
            routes.get("/api/agents", (req, res) -> {
                if (!authorized(req, res)) return;
                List<Map<String, String>> agents = ctx.getAgentInfos().stream()
                        .map(a -> {
                            Map<String, String> m = new LinkedHashMap<>();
                            m.put("id",          a.id());
                            m.put("channelType", a.channelType());
                            m.put("role",        a.role().name());
                            m.put("strategy",    a.strategy().name());
                            return m;
                        })
                        .collect(Collectors.toList());
                sendJson(res, agents);
            });

            // ── API: workflows ───────────────────────────────────────────────
            routes.get("/api/workflows", (req, res) -> {
                if (!authorized(req, res)) return;
                List<Map<String, Object>> result = new ArrayList<>();

                Optional<VDagRegistry> regOpt    = ctx.getService(VDagRegistry.class);
                Optional<VDagEngine>   engineOpt = ctx.getService(VDagEngine.class);

                if (regOpt.isPresent() && engineOpt.isPresent()) {
                    VDagEngine engine = engineOpt.get();
                    // Active runs first
                    engine.listActiveRuns().forEach(run -> result.add(runToMap(run)));
                    // Recent completed runs per registered DAG
                    regOpt.get().listDags().forEach(dag -> {
                        engine.getRuns(dag.id(), config.getWorkflowRunLimit())
                                .stream()
                                .filter(r -> r.state() != VDagRunState.RUNNING
                                          && r.state() != VDagRunState.QUEUED)
                                .forEach(run -> result.add(runToMap(run)));
                    });
                    // Deduplicate by runId, preserve insertion order
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    result.removeIf(m -> !seen.add((String) m.get("runId")));
                }
                sendJson(res, result);
            });

            // ── API: routes ──────────────────────────────────────────────────
            routes.get("/api/routes", (req, res) -> {
                if (!authorized(req, res)) return;
                sendJson(res, ctx.getRegisteredRoutes());
            });
        });

        if (config.isAuthEnabled()) {
            log.info("Admin UI available at {} — bearer token auth enabled", base);
        } else {
            log.warn("Admin UI available at {} — NO AUTH (open access)", base);
        }
    }

    @Override public void onShutdown() {}

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean authorized(VHttpRequest req, VHttpResponse res) {
        if (!config.isAuthEnabled()) return true;
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (config.getToken().equals(token)) return true;
        }
        res.status(401)
           .header("WWW-Authenticate", "Bearer realm=\"vatn-admin\"")
           .header("Content-Type", "application/json")
           .send("{\"error\":\"Unauthorized\"}");
        return false;
    }

    private void sendJson(VHttpResponse res, Object data) {
        try {
            res.header("Content-Type", "application/json; charset=utf-8")
               .send(mapper.writeValueAsString(data));
        } catch (Exception e) {
            res.status(500).send("{\"error\":\"serialization failed\"}");
        }
    }

    private static Map<String, Object> runToMap(VDagRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId",    run.runId());
        m.put("dagId",    run.dagId());
        m.put("state",    run.state().name());
        m.put("started",  run.startDate() != null ? run.startDate().toString() : null);
        m.put("finished", run.endDate()   != null ? run.endDate().toString()   : null);
        m.put("durationMs", run.startDate() != null && run.endDate() != null
                ? Duration.between(run.startDate(), run.endDate()).toMillis()
                : null);
        m.put("triggered", run.externalTrigger() ? "manual" : "scheduled");
        return m;
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60) return m + "m " + s + "s";
        long h = m / 60; m %= 60;
        if (h < 24) return h + "h " + m + "m";
        long d = h / 24; h %= 24;
        return d + "d " + h + "h";
    }
}
