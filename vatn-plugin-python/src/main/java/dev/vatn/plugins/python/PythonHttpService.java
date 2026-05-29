package dev.vatn.plugins.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vatn.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST + admin UI for the Python plugin, mounted at {@code /python}.
 *
 * <pre>
 * GET  /python/status                 — runtime info (Python version, uv, conda, envs)
 * GET  /python/envs                   — list virtual environments
 * POST /python/envs/{name}            — create a venv
 * DELETE /python/envs/{name}          — delete a venv
 * GET  /python/apps                   — list app directories + their scripts
 * POST /python/apps/{name}/run        — execute a pinokio.json script (body: optional overrides)
 * GET  /python/apps/{name}/processes  — list running processes for an app
 * GET  /python/processes              — all running processes
 * GET  /python/processes/{id}/status  — process status + log tail
 * POST /python/processes/{id}/stop    — stop a process
 * GET  /python/ui                     — admin web UI (HTML)
 * </pre>
 */
class PythonHttpService implements VHttpService {

    private static final Logger log = LoggerFactory.getLogger(PythonHttpService.class);

    private final PythonRuntime        runtime;
    private final PythonProcessManager manager;
    private final ObjectMapper         mapper  = new ObjectMapper();
    private final String               adminUi;

    PythonHttpService(PythonRuntime runtime, PythonProcessManager manager) {
        this.runtime = runtime;
        this.manager = manager;
        this.adminUi = loadAdminUi();
    }

    @Override
    public void routing(VHttpRoutes routes) {
        routes.get("/status",                       this::handleStatus);
        routes.get("/envs",                         this::handleListEnvs);
        routes.post("/envs/{name}",                 this::handleCreateEnv);
        routes.delete("/envs/{name}",               this::handleDeleteEnv);
        routes.get("/apps",                         this::handleListApps);
        routes.post("/apps/{name}/run",             this::handleRunApp);
        routes.get("/apps/{name}/processes",        this::handleAppProcesses);
        routes.get("/processes",                    this::handleListProcesses);
        routes.get("/processes/{id}/status",        this::handleProcessStatus);
        routes.post("/processes/{id}/stop",         this::handleStopProcess);
        routes.get("/ui",                           this::handleUi);
    }

    private void handleStatus(VHttpRequest req, VHttpResponse res) {
        res.sendJson(toJson(Map.of(
            "python",        runtime.pythonBinary(),
            "version",       runtime.pythonVersion(),
            "healthy",       runtime.isHealthy(),
            "uv",            runtime.uvAvailable() ? runtime.uvVersion() : "not found",
            "conda",         runtime.condaAvailable() ? "available" : "not found",
            "envCount",      runtime.envNames().size(),
            "processCount",  manager.getAll().size()
        )));
    }

    private void handleListEnvs(VHttpRequest req, VHttpResponse res) {
        var envs = runtime.envNames().stream()
            .map(name -> Map.of("name", name,
                "path", runtime.envsDir().resolve(name).toString()))
            .collect(Collectors.toList());
        res.sendJson(toJson(Map.of("envs", envs, "count", envs.size())));
    }

    private void handleCreateEnv(VHttpRequest req, VHttpResponse res) {
        String name = req.getPathParam("name");
        try {
            Path path = runtime.createEnv(name);
            res.status(201).sendJson(toJson(Map.of("name", name, "path", path.toString(), "status", "created")));
        } catch (Exception e) {
            res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleDeleteEnv(VHttpRequest req, VHttpResponse res) {
        String name = req.getPathParam("name");
        try {
            runtime.deleteEnv(name);
            res.sendJson("{\"status\":\"deleted\",\"name\":\"" + name + "\"}");
        } catch (Exception e) {
            res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleListApps(VHttpRequest req, VHttpResponse res) {
        try {
            Path appsDir = runtime.appsDir();
            var apps = new ArrayList<Map<String, Object>>();
            if (Files.isDirectory(appsDir)) {
                try (var stream = Files.list(appsDir)) {
                    stream.filter(Files::isDirectory).forEach(appDir -> {
                        String name    = appDir.getFileName().toString();
                        boolean hasScript = Files.exists(appDir.resolve("pinokio.json"))
                                         || Files.exists(appDir.resolve("pinokio.js"));
                        apps.add(Map.of(
                            "name", name,
                            "path", appDir.toString(),
                            "hasScript", hasScript,
                            "runningProcesses", manager.getByApp(name).size()
                        ));
                    });
                }
            }
            res.sendJson(toJson(Map.of("apps", apps, "count", apps.size())));
        } catch (Exception e) {
            res.status(500).sendJson("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private void handleRunApp(VHttpRequest req, VHttpResponse res) {
        String appName = req.getPathParam("name");
        try {
            Path appDir    = runtime.appsDir().resolve(appName);
            Path scriptPath = Files.exists(appDir.resolve("pinokio.json"))
                ? appDir.resolve("pinokio.json")
                : appDir.resolve("pinokio.js");

            if (!Files.exists(scriptPath)) {
                res.status(404).sendJson("{\"error\":\"No pinokio.json found in app '" + appName + "'\"}");
                return;
            }

            PynokioScript script = PynokioScript.fromFile(scriptPath);
            var runner = new PynokioScriptRunner(runtime, manager, appName, appDir);
            var result = runner.run(script);
            res.sendJson(toJson(result));
        } catch (Exception e) {
            log.error("[PYTHON] Failed to run app '{}': {}", appName, e.getMessage(), e);
            res.status(500).sendJson("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private void handleAppProcesses(VHttpRequest req, VHttpResponse res) {
        String appName = req.getPathParam("name");
        var procs = manager.getByApp(appName).stream()
            .map(this::processToMap)
            .collect(Collectors.toList());
        res.sendJson(toJson(Map.of("appId", appName, "processes", procs)));
    }

    private void handleListProcesses(VHttpRequest req, VHttpResponse res) {
        var all = manager.getAll().stream()
            .map(this::processToMap)
            .collect(Collectors.toList());
        res.sendJson(toJson(Map.of("processes", all, "count", all.size())));
    }

    private void handleProcessStatus(VHttpRequest req, VHttpResponse res) {
        String id = req.getPathParam("id");
        manager.get(id).ifPresentOrElse(p -> {
            var m = processToMap(p);
            ((Map<String, Object>) m).put("logs", p.getLogTail(50));
            res.sendJson(toJson(m));
        }, () -> res.status(404).sendJson("{\"error\":\"Process '" + id + "' not found\"}"));
    }

    private void handleStopProcess(VHttpRequest req, VHttpResponse res) {
        String id = req.getPathParam("id");
        manager.get(id).ifPresentOrElse(p -> {
            p.stop();
            res.sendJson("{\"status\":\"stopping\",\"id\":\"" + id + "\"}");
        }, () -> res.status(404).sendJson("{\"error\":\"Process not found\"}"));
    }

    private void handleUi(VHttpRequest req, VHttpResponse res) {
        res.header("Content-Type", "text/html; charset=UTF-8");
        res.send(adminUi);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> processToMap(PythonProcess p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id",           p.id());
        m.put("appId",        p.appId());
        m.put("status",       p.status().name().toLowerCase());
        m.put("pid",          p.pid());
        m.put("restartCount", p.restartCount());
        m.put("lastExitCode", p.lastExitCode());
        m.put("startedAt",    p.startedAt() != null ? p.startedAt().toString() : null);
        m.put("autoRestart",  p.autoRestart());
        return m;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private static String loadAdminUi() {
        try (var is = PythonHttpService.class.getResourceAsStream("/python-admin.html")) {
            if (is != null) return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        return "<html><body><p>Admin UI not found. Check python-admin.html in classpath.</p></body></html>";
    }
}
