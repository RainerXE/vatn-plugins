# VATN Plugins

Drop-in plugins for the [VATN runtime](https://github.com/RainerXE/vatn).

Each plugin implements `VNodePlugin` and is registered with `VNodeRunner.addPlugin()` before calling `.start()`. Plugins declare their dependencies by consuming services from `VNodeContext` — no direct coupling between plugins.

---

## Plugin Catalog

### Infrastructure

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-postgres](vatn-plugin-postgres/) | `vatn-plugin-postgres` | PostgreSQL connection pool via HikariCP. Registers `DataSourceService` and a `postgres` health check. |
| [vatn-plugin-redis](vatn-plugin-redis/) | `vatn-plugin-redis` | Redis client via Jedis. Registers `RedisService` with get/set/del/expire/pub-sub. |
| [vatn-plugin-mongodb](vatn-plugin-mongodb/) | `vatn-plugin-mongodb` | MongoDB sync driver. Registers `MongoService` giving access to collections and the `MongoDatabase`. |
| [vatn-plugin-s3](vatn-plugin-s3/) | `vatn-plugin-s3` | S3-compatible object storage via AWS SDK v2. Supports AWS S3, MinIO, Cloudflare R2, DigitalOcean Spaces. Registers `S3Service` with upload/download/presign/delete. |
| [vatn-plugin-email](vatn-plugin-email/) | `vatn-plugin-email` | SMTP email via Jakarta Mail (Angus). Registers `EmailService` with plain-text and HTML send. Supports STARTTLS, SSL, and app passwords. |
| [vatn-plugin-metrics](vatn-plugin-metrics/) | `vatn-plugin-metrics` | Prometheus metrics via Micrometer. Registers `MetricsService` (MeterRegistry) and exposes `GET /metrics` in Prometheus text format. Optional JVM metrics (GC, memory, threads, CPU). |

### Security & Auth

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-auth](vatn-plugin-auth/) | `vatn-plugin-auth` | JWT authentication. Registers `AuthService` and three endpoints: `POST /auth/login`, `POST /auth/refresh`, `GET /auth/me`. Supply a credential validator and secret key. |
| [vatn-plugin-bcrypt](vatn-plugin-bcrypt/) | `vatn-plugin-bcrypt` | BCrypt password hashing. Registers `BcryptService` with hash/verify. Configurable cost factor (default 12 ≈ 250 ms). Pairs with `vatn-plugin-auth`. |
| [vatn-plugin-cors](vatn-plugin-cors/) | `vatn-plugin-cors` | CORS filter (order 150). Adds `Access-Control-*` headers and handles OPTIONS preflight. Permissive defaults or explicit origin allowlist. |
| [vatn-plugin-security](vatn-plugin-security/) | `vatn-plugin-security` | HTTP security headers filter. Injects `X-Frame-Options`, `X-Content-Type-Options`, `Content-Security-Policy`, `HSTS`, and `Referrer-Policy`. Configurable CSP and HSTS values. |

### API & UI

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-swagger](vatn-plugin-swagger/) | `vatn-plugin-swagger` | Swagger / OpenAPI UI. Serves `GET /api-docs` (OpenAPI 3.0 JSON) and `GET /docs` (Swagger UI via CDN). Accepts a pre-built spec or generates a minimal skeleton from config metadata. |

### AI & Data

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-openai](vatn-plugin-openai/) | `vatn-plugin-openai` | LLM client for any OpenAI-compatible API. Registers `LlmService` with `complete()` and `chat()`. Supports OpenAI, Anthropic/Claude, Ollama, and any compatible endpoint. |
| [vatn-plugin-scraper](vatn-plugin-scraper/) | `vatn-plugin-scraper` | HTML scraper backed by Jsoup. Fetches a list of URLs, extracts structured entries, and pipes results as NDJSON to a downstream VATN node via `VStream`. |
| [vatn-plugin-indexer](vatn-plugin-indexer/) | `vatn-plugin-indexer` | Stream processor that receives a JSON stream, sorts entries by title, and relays them downstream. Designed as a pipeline stage between scraper and storage nodes. |

### Communication

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-slack](vatn-plugin-slack/) | `vatn-plugin-slack` | Slack notifications via Incoming Webhook. Registers `SlackService` with `notify(message)`. Stateless — no persistent connection. |
| [vatn-plugin-comm](vatn-plugin-comm/) | `vatn-plugin-comm` | **Messaging sidecar hub** for Telegram, Signal, and RCS. Each channel runs as a `VAgent` with optional active-passive failover. Unified `CommService` API for send/receive across all channels. See below. |
| [vatn-plugin-terminalphone](vatn-plugin-terminalphone/) | `vatn-plugin-terminalphone` | **Anonymous E2E-encrypted voice and text over Tor.** Record-and-send voice clips, encrypted text messaging, and a zero-knowledge group relay — all routed through `.onion` addresses. Inspired by [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone). See below. |

### Protocols

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-activitypub](vatn-plugin-activitypub/) | `vatn-plugin-activitypub` | ActivityPub federation. Exposes `/.well-known/webfinger`, `/ap/actor`, `/ap/inbox`, `/ap/outbox`. Handles Follow/Undo activities and sends HTTP-signed Accept responses. RSA-2048 key management built in. |

### Runtime Extensions

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-wasm](vatn-plugin-wasm/) | `vatn-plugin-wasm` | **Sandboxed WASM execution.** Registers `VWasmRuntime` so any plugin can load and call `.wasm` modules. Engine: [Chicory](https://github.com/dylibso/chicory) (pure Java, zero JNI). Supports WASI p1 for Rust/C/Go/Zig binaries with capability-scoped filesystem. Clean swap point for GraalWASM. See below. |

---

## Quick start

### 1. Install VATN to your local Maven repo

```sh
cd /path/to/vatn && mvn install -DskipTests
```

### 2. Add the parent BOM (optional but recommended)

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.vatn.plugins</groupId>
      <artifactId>vatn-plugins-parent</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### 3. Add the plugins you need

```xml
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-postgres</artifactId>
</dependency>
<dependency>
    <groupId>dev.vatn.plugins</groupId>
    <artifactId>vatn-plugin-auth</artifactId>
</dependency>
<!-- add more as needed -->
```

### 4. Register with the node runner

```java
VNodeRunner.create(8080)
    .addPlugin(new SecurityPlugin())
    .addPlugin(new CorsPlugin())
    .addPlugin(new PostgresPlugin(PostgresConfig.of("localhost", 5432, "mydb", "user", "pass")))
    .addPlugin(new AuthPlugin(AuthConfig.of(jwtSecret, myCredentialValidator)))
    .addPlugin(new MetricsPlugin())
    .addPlugin(new SwaggerPlugin(SwaggerConfig.of("My API", "1.0.0")))
    .addPlugin(new MyAppPlugin())
    .start();
```

Plugin load order follows registration order. Plugins that depend on a service registered by another plugin should be added after it.

---

## Plugin detail: vatn-plugin-comm

The communication sidecar manages external messaging channels as `VAgent` instances — long-running background components with built-in active-passive failover.

```java
VNodeRunner.create(8080)
    .addPlugin(new CommPlugin(CommConfig.create()

        // Telegram: long-polling, active-passive failover
        .withTelegram(TelegramConfig.polling(System.getenv("TELEGRAM_TOKEN"))
            .withAgentMode(VAgentMode.activePassive().withFailoverTimeout(10_000)))

        // Signal: via signal-cli-rest-api sidecar
        .withSignal(SignalConfig.of("http://signal-api:8080", System.getenv("SIGNAL_NUMBER"))
            .withAgentMode(VAgentMode.activePassive()))

        // RCS: Twilio provider, webhook inbound
        .withRcs(RcsConfig.twilio(System.getenv("TWILIO_FROM"),
            System.getenv("TWILIO_SID"), System.getenv("TWILIO_TOKEN")))
    ))
    .addPlugin(new MyBotPlugin())
    .start();
```

In your plugin, get `CommService` from context:

```java
CommService comm = ctx.getService(CommService.class).orElseThrow();

// Receive from all channels
comm.onMessage(msg ->
    comm.send(OutboundMessage.replyTo(msg, "Echo: " + msg.text())));

// Send explicitly
comm.send(OutboundMessage.text(CommChannel.TELEGRAM, chatId, "Alert: pipeline failed"));
```

See [vatn-plugin-comm/README.md](vatn-plugin-comm/README.md) for full documentation including Signal sidecar setup, RCS provider config, and the twin pattern for load-balanced webhook receivers.

---

## Plugin detail: vatn-plugin-terminalphone

A VATN-native port of [TerminalPhone](https://gitlab.com/here_forawhile/terminalphone) by *here_forawhile* — a self-contained Bash walkie-talkie that runs entirely over Tor. The plugin replicates the same security model on the JVM: record a complete voice clip, encrypt it with AES-256-CBC and sign it with HMAC-SHA256, then deliver it through the Tor SOCKS5 proxy to a peer's `.onion` address. No accounts, no servers, no cleartext.

```java
VNodeRunner.create(8080)
    .addPlugin(new TerminalPhonePlugin(
        TerminalPhoneConfig.builder("exchange-this-secret-out-of-band")
            .cipher("AES-256-CBC")
            .torPort(9050)
            .listenPort(54321)
            .hmacSigning(true)
            .build()
    ))
    .addPlugin(new MyPlugin())
    .start();
```

In your plugin, get `TerminalPhoneService` from context:

```java
TerminalPhoneService phone = ctx.getService(TerminalPhoneService.class).orElseThrow();

// Share your address with the peer (scan the QR out-of-band)
System.out.println(phone.getQrCode());

// Wire up handlers
phone.onCallConnected(peer -> log.info("Connected to {}", peer));
phone.onTextMessage(msg   -> log.info(">> {}", msg));
phone.onVoiceMessage(pcm  -> phone.playVoice(pcm));

// Dial and talk
phone.call("abc123.onion");
byte[] clip = phone.recordVoice(3_000);  // 3s push-to-talk
phone.sendVoice(clip);
phone.sendText("On my way.");
```

Group calls are supported via **relay mode** — a second listener forwards encrypted frames between callers without decrypting them (zero-knowledge relay). Enable with `.relayMode(true)`.

See [vatn-plugin-terminalphone/README.md](vatn-plugin-terminalphone/README.md) for the full security model, configuration reference, and HTTP endpoint documentation.

---

## Plugin detail: vatn-plugin-wasm

Sandboxed WebAssembly execution using [Chicory](https://github.com/dylibso/chicory) — a pure-Java, zero-JNI WASM runtime. Registers `VWasmRuntime` in the node context so any plugin can load and invoke `.wasm` modules without importing the plugin as a compile-time dependency.

```java
VNodeRunner.create(8080)
    .addPlugin(new WasmPlugin())          // default: auto-loads .vatn/wasm/*.wasm
    .addPlugin(new MyPlugin())
    .start();
```

In your plugin:

```java
VWasmRuntime wasm = ctx.getService(VWasmRuntime.class).orElseThrow();

// Load from bytes (or use auto-loaded module by name)
VWasmModule mod = wasm.load("verifier", Files.readAllBytes(wasmPath));

// Call an exported integer function
long[] result = mod.call("add", 40L, 2L);   // → [42]

// Run a WASI binary (Rust/C/Go/Zig) with scoped filesystem + stdout capture
String output = mod.callWasi(new String[]{"check", "src/main.odin"}, null);
```

**Sandbox model**: WASM linear-memory isolation (Chicory) + WASI capability grants (filesystem scoped to workspace, no network) + `VSubprocessAuditService` (every call logged).

**Upgrading Chicory**: one property in `vatn-plugins-parent/pom.xml` — `<chicory.version>`. Change it and rebuild.

**Switching to GraalWASM**: replace `new ChicoryWasmRuntime(...)` in `WasmPlugin` with a `GraalWasmRuntime` that implements the same `VWasmRuntime` SPI. All callers continue working unchanged. GraalWASM compiles WASM to native machine code on GraalVM JDK — peak performance when you need it.

See [vatn-plugin-wasm/README.md](vatn-plugin-wasm/README.md) for configuration, REST API, language examples (Rust/C/Go/Zig), and the GraalWASM migration path.

---

## Recommended stacks

### Web API

```java
SecurityPlugin  →  CorsPlugin  →  AuthPlugin  →  PostgresPlugin  →  MetricsPlugin  →  SwaggerPlugin  →  YourPlugin
```

### AI assistant with notifications

```java
PostgresPlugin  →  RedisPlugin  →  OpenAiPlugin  →  CommPlugin  →  YourBotPlugin
```

### Data pipeline

```java
ScraperPlugin  →  IndexerPlugin  →  S3Plugin  →  MetricsPlugin
```

### Federated social node

```java
SecurityPlugin  →  PostgresPlugin  →  ActivityPubPlugin  →  CommPlugin  →  YourPlugin
```

### Agentic tool runtime (native code in the JVM)

```java
WasmPlugin  →  PostgresPlugin  →  MetricsPlugin  →  YourAgentPlugin
// YourAgentPlugin loads .wasm verifiers, policy engines, or domain tools via VWasmRuntime
```

---

## Building

```sh
# Requires vatn-api in local Maven repo (see step 1 above)
mvn install -DskipTests
```
