# vatn-plugin-python

Sandboxed Python process management for VATN nodes — with **Pinokio-compatible `run[]` script format**, virtual environment management, and a live admin UI.

**Admin UI**: `GET /python/ui`  
**Runtime**: VATN 1.0-alpha.11+

---

## What It Does

- Detects the system Python (`python3`, `uv`, `conda`) at startup and reports via health check
- Manages named virtual environments at `.vatn/python/envs/<name>/`
- Executes **Pinokio-format `run[]` scripts** — the same JSON used by [Pinokio](https://github.com/pinokiocomputer/pinokio) AI app installers
- Runs `daemon: true` steps as supervised processes with auto-restart on crash
- Captures stdout/stderr in a circular log buffer, queryable via REST
- Exposes a lightweight admin web UI (dark theme, live refresh, log streaming)

---

## Quick Start

```java
VNodeRunner.create(8080)
    .addPlugin(new PythonPlugin())
    .start();
```

Drop a `pinokio.json` in `.vatn/python/apps/<name>/` and call `POST /python/apps/<name>/run`.

### From another plugin

```java
PythonRuntime       python = ctx.getService(PythonRuntime.class).orElseThrow();
PythonProcessManager procs = ctx.getService(PythonProcessManager.class).orElseThrow();

// Create a venv and install packages
python.createEnv("myapp");

// Execute a Pinokio script
Path appDir = ctx.getWorkspacePath().resolve(".vatn/python/apps/myapp");
var script  = PynokioScript.fromFile(appDir.resolve("pinokio.json"));
var runner  = new PynokioScriptRunner(python, procs, "myapp", appDir);
var result  = runner.run(script);
// result → {status:"completed", stepsRun:3, daemonProcessIds:["myapp-a1b2c3d4"]}
```

---

## Pinokio Script Format

Scripts are JSON files with a `run[]` array — the same format used by Pinokio AI app launchers.

```json
{
  "run": [
    {
      "method": "shell.run",
      "params": {
        "message": "uv pip install -r requirements.txt",
        "path": "./app",
        "venv": "myenv"
      }
    },
    {
      "method": "shell.run",
      "params": {
        "message": "python server.py --port 8081",
        "path": "./app",
        "venv": "myenv",
        "daemon": true,
        "autoRestart": true
      }
    }
  ]
}
```

### Supported methods

| Method | What it does | Key params |
|--------|-------------|------------|
| `shell.run` | Run a shell command | `message`, `path`, `venv`, `conda`, `env`, `daemon`, `autoRestart` |
| `fs.write` | Write a file (scoped to app root) | `path`, `data` |
| `fs.read` | Read a file, store in `id` | `path` |
| `local.set` | Set a session variable | `key`, `value` |
| `local.get` | Get a session variable into `id` | `key` |
| `script.return` | Stop script execution | — |

### `shell.run` params

| Param | Type | Description |
|-------|------|-------------|
| `message` | string | Shell command to execute |
| `path` | string | Working directory (relative to app root) |
| `venv` | string | Venv name — created automatically if missing |
| `conda` | string | Conda env name (if conda is available) |
| `env` | object | Additional environment variables |
| `daemon` | boolean | Keep running after script ends |
| `autoRestart` | boolean | Auto-restart on crash (requires `daemon: true`) |

### Template variables

Reference outputs from earlier steps:
```json
{ "method": "fs.read", "params": { "path": "./config.json" }, "id": "cfg" },
{ "method": "shell.run", "params": { "message": "echo {{cfg}}" } }
```

---

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/python/status` | Python version, uv/conda availability, counts |
| `GET`  | `/python/envs` | List virtual environments |
| `POST` | `/python/envs/{name}` | Create a venv |
| `DELETE` | `/python/envs/{name}` | Delete a venv |
| `GET`  | `/python/apps` | List app directories |
| `POST` | `/python/apps/{name}/run` | Execute the app's `pinokio.json` |
| `GET`  | `/python/apps/{name}/processes` | List running processes for an app |
| `GET`  | `/python/processes` | All running processes |
| `GET`  | `/python/processes/{id}/status` | Status + last 50 log lines |
| `POST` | `/python/processes/{id}/stop` | Stop a process |
| `GET`  | `/python/ui` | Admin web UI |

---

## Configuration

```java
new PythonPlugin(PythonConfig.builder()
    .pythonBinary("python3.12")        // override auto-detection
    .envsDir(Paths.get(".vatn/python/envs"))
    .appsDir(Paths.get(".vatn/python/apps"))
    .preferUv(true)                    // use uv pip install (faster than pip)
    .restartDelayMs(3_000)             // wait before auto-restart
    .maxLogLines(500)                  // circular log buffer per process
    .allowedEnvVars(List.of("PATH","HOME","LANG"))
    .build())
```

---

## Package installer priority

1. `uv pip install` — preferred if `uv` is installed (10-100× faster than pip)
2. `pip install` — fallback
3. `conda install` — when `conda` param is specified and conda is available

---

## Pinokio compatibility

This plugin reads Pinokio `run[]` scripts directly. Compatible with apps distributed as Pinokio packages — drop a `pinokio.json` into `.vatn/python/apps/<name>/` and run it.

**Pinokio features supported by this plugin:** `shell.run` (with `venv`, `conda`, `env`, `daemon`), `fs.write`, `fs.read`, `local.set/get`, `script.return`.

**Not supported** (Pinokio-specific): marketplace/browser, download management, dynamic JS scripts (`pinokio.js` module exports).
