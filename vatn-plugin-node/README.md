# vatn-plugin-node

Sandboxed Node.js process management for VATN nodes — same `run[]` script format as `vatn-plugin-python`, supervised process lifecycle with auto-restart, and a live admin UI.

**Admin UI**: `GET /node/ui`  
**Runtime**: VATN 1.0-alpha.11+

---

## What It Does

- Detects the system Node.js, npm, and npx at startup
- Manages per-app npm directories at `.vatn/node/apps/<name>/`
- Executes `run[]` scripts — same JSON format as `vatn-plugin-python` for consistency
- Runs `daemon: true` steps as supervised Node.js processes with auto-restart on crash
- Captures stdout/stderr in a circular log buffer
- Supports `npm install`, `npx` commands, and arbitrary shell commands
- Exposes a live admin web UI with process controls and log streaming

---

## Quick Start

```java
VNodeRunner.create(8080)
    .addPlugin(new NodePlugin())
    .start();
```

Drop a `vatn-node.json` in `.vatn/node/apps/<name>/` and call `POST /node/apps/<name>/run`.

### From another plugin

```java
NodeRuntime        node  = ctx.getService(NodeRuntime.class).orElseThrow();
NodeProcessManager procs = ctx.getService(NodeProcessManager.class).orElseThrow();

Path appDir = ctx.getWorkspacePath().resolve(".vatn/node/apps/myapp");
var script  = NodeScriptRunner.NodeScript.fromFile(appDir.resolve("vatn-node.json"));
var runner  = new NodeScriptRunner(node, procs, "myapp", appDir);
var result  = runner.run(script);
// result → {status:"completed", stepsRun:3, daemonProcessIds:["myapp-a1b2c3d4"]}
```

---

## Script Format (`vatn-node.json`)

Same `run[]` array format as `vatn-plugin-python`. Uses `vatn-node.json` or `pinokio.json` as the filename.

```json
{
  "run": [
    {
      "method": "npm.install",
      "params": { "packages": ["express", "dotenv"] }
    },
    {
      "method": "fs.write",
      "params": {
        "path": ".env",
        "data": "PORT=3000\nNODE_ENV=production\n"
      }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "node server.js",
        "daemon": true,
        "autoRestart": true,
        "env": { "PORT": "3000" }
      }
    }
  ]
}
```

### Supported methods

| Method | What it does | Key params |
|--------|-------------|------------|
| `shell.run` | Run any shell command | `message`, `path`, `env`, `daemon`, `autoRestart` |
| `npm.install` | `npm install [packages]` in app dir | `packages` (optional list) |
| `npx.run` | `npx <command>` in app dir | `message` |
| `fs.write` | Write a file (scoped to app root) | `path`, `data` |
| `fs.read` | Read a file, store in `id` | `path` |
| `local.set` | Set a session variable | `key`, `value` |
| `local.get` | Get a session variable into `id` | `key` |
| `script.return` | Stop script execution | — |

### `shell.run` command substitution

The runner automatically replaces `node`, `npm`, `npx` with the detected binaries, so scripts are portable across environments:
```json
{ "method": "shell.run", "params": { "message": "node --version" } }
```

---

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/node/status` | Node.js version, npm, app/process counts |
| `GET`  | `/node/apps` | List app directories |
| `POST` | `/node/apps/{name}/install` | `npm install` in app dir (body: `{"packages":["express"]}`) |
| `POST` | `/node/apps/{name}/run` | Execute the app's `vatn-node.json` |
| `GET`  | `/node/apps/{name}/processes` | Running processes for an app |
| `GET`  | `/node/processes` | All running processes |
| `GET`  | `/node/processes/{id}/status` | Status + last 50 log lines |
| `POST` | `/node/processes/{id}/stop` | Stop a process |
| `POST` | `/node/processes/{id}/restart` | Restart a stopped process |
| `GET`  | `/node/ui` | Admin web UI |

---

## Configuration

```java
new NodePlugin(NodeConfig.builder()
    .nodeBinary("node")               // override auto-detection
    .npmBinary("npm")
    .npxBinary("npx")
    .appsDir(Paths.get(".vatn/node/apps"))
    .restartDelayMs(3_000)
    .maxLogLines(500)
    .allowNetwork(true)               // network allowed for daemon processes
    .allowedEnvVars(List.of("PATH","HOME","NODE_ENV","CI"))
    .build())
```

---

## Why not Node-RED?

Node-RED is a visual flow-based programming tool for IoT/automation — not a general-purpose Node.js process runner. `vatn-plugin-node` runs **any** Node.js application or script, including Express servers, CLI tools, workers, and scrapers, with the same simple `run[]` format you use for Python apps.

---

## Why not PM2?

PM2 is excellent but adds a separate daemon process you don't control from Java. `vatn-plugin-node` implements the same lifecycle concepts (start/stop/restart/auto-restart/logs) in Java virtual threads, giving you: full JVM observability, `VSubprocessAuditService` integration, OS-level sandboxing via `VTrustLevel`, and unified admin across Python + Node from the same VATN node.
