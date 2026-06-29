# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo contains two **independent** Java 17 Agent demo applications that contrast two implementation philosophies:

- **`pimono-demo/`** — Minimal hand-written Agent (~700 lines, 8 main classes). Teaching/prototype oriented. ReAct loop + OpenAI-compatible client + MCP stdio client, no framework abstractions.
- **`agentscope-demo/`** — Full Java reimplementation of AgentScope 2.0's "六件套" architecture (~2500 lines, 30+ classes). Production-oriented: event stream, middleware onion, three-state permission engine, workspace sandbox, credential provider with failover, Leader-Worker agent teams.

Both are Maven submodules under a parent POM (`com.demo:agent-demos:1.0.0`). There is **no shared library module** — common concepts (ReAct loop, LLM client, MCP) are duplicated intentionally for didactic contrast. See `ARCHITECTURE.md` for the side-by-side comparison.

## Build, Run, Test

JDK 17 and Maven are vendored locally under `.tools/` (gitignored). The `start*.sh` scripts (also gitignored — they contain personal API keys) set up `JAVA_HOME`/`PATH` and launch the shaded jars.

```bash
# Build both modules
mvn clean compile
mvn package -DskipTests        # produces agentscope-demo-1.0.0.jar / pimono-demo-1.0.0.jar (shaded)

# Run tests (all modules)
mvn test

# Run a single test class
mvn test -pl agentscope-demo -Dtest=PermissionTest
mvn test -pl pimono-demo -Dtest=AgentCoreTest

# Run a single test method
mvn test -pl agentscope-demo -Dtest=PermissionTest#specificMethod
```

Required env vars before running either app (read by `DefaultCredentialProvider` / `PiMonoDemoApplication`):

| Variable | Purpose |
|---|---|
| `OPENAI_API_KEY` / `DASHSCOPE_API_KEY` / `ANTHROPIC_API_KEY` / `DEEPSEEK_API_KEY` | At least one required |
| `MODEL_PROVIDER` | Auto-detected if omitted (agentscope prefers dashscope > openai) |
| `MODEL_NAME` | e.g. `gpt-4o-mini`, `glm-5.1` |
| `MODEL_BASE_URL` | OpenAI-compatible base URL |
| `MCP_SERVERS` | Optional, format `command:arg1,arg2` |
| `WORKSPACE_DIR` | Default `workspace/` (agentscope only) |
| `REPLY_BUDGET` | Token budget cap (agentscope only) |

Both apps are interactive REPLs — pipe a prompt via stdin or run them in a terminal.

## Architecture (Big Picture)

### Shared Agent substrate (both modules)

The five layers every Agent needs, and where each module implements them:

| Layer | pimono | agentscope |
|---|---|---|
| ReAct loop | `AgentCore.chat()` `while (round < MAX_TOOL_ROUNDS)` | `Agent.reply()` — loop body split across middleware hooks |
| LLM client | `ai/LlmClient` (OkHttp + Jackson, OpenAI `/chat/completions`) | `model/ChatModel` (multi-backend) |
| MCP client | `mcp/McpServerConnection` (JSON-RPC 2.0 over stdio) | `mcp/McpServerConnection` + `MCPClient` (declarative Stdio/Http config) |
| Context | `context/ContextManager` FIFO sliding window (50 msgs) | `middleware/ContextCompressionMiddleware` structured compression |
| Tool-call metadata | `Context.ToolCallEntry` | `message/ContentBlock` (6 block types incl. ToolCall/ToolResult) |

`tool_call_id` linkage between request and result is mandatory in both — never drop it.

### agentscope-demo layered architecture

Entry point: `com.demo.agentscope.AgentScopeDemoApplication`. Its `main()` is the canonical wiring example — follow that order when modifying:

1. **Credential** (`credential/`) — `DefaultCredentialProvider` resolves API keys via env vars, supports primary/standby failover.
2. **MCP** (`mcp/`) — `MCPClient` aggregates builtin tools + external stdio MCP servers parsed from `MCP_SERVERS`. Also gets the file/code-execution tools registered later.
3. **FilePermission + Workspace** (`filepermission/`, `workspace/`) — Two independent permission layers:
   - `FilePermissionManager` (path-level, glob patterns, extension denylist, 10MB cap, default DENY_ALL) wraps `LocalWorkspace` via `SecureFileWorkspace`.
   - `workspace/` provides `LocalWorkspace` (real) plus `DockerWorkspace` / `E2BWorkspace` (stubs) and `WorkspaceManager` for multi-tenant isolation.
4. **Code execution** (`execution/`) — `CodeExecutionManager` runs python/shell with `CommandSafetyChecker` blocking dangerous patterns and a 30s timeout.
5. **Permission** (`permission/`) — `PermissionEngine` chains **Rules → Mode → Built-in** (any non-ALLOW terminates). Three-state decisions: `ALLOW`/`DENY`/`ASK`. Modes: `EXPLORE` (read-only), `DONT_ASK` (ASK→DENY, the app default), `BYPASS` (skip). `PermissionMiddleware` plugs this into the middleware chain.
6. **Agent** (`agent/Agent.java`) — unified `reply()` / `replyStream()`. `AgentTeam` gives the leader four team-management tools (`agent_create`, `agent_message`, `agent_list`, `team_dissolve`) so it dynamically spawns worker sessions rather than using a static DAG.
7. **Middleware onion** (`middleware/`) — 6 hook points (`onReplyStart`, `onModelCall`, `onModelCallEnd`, `onToolCall`, `onToolResult`, `onReplyEnd`). Built-in: `TracingMiddleware`, `ContextCompressionMiddleware`, `PermissionMiddleware`, `ReplyBudgetControlMiddleware` (throws `BudgetExceededException`).
8. **Events** (`event/`) — `EventStream` is the observability spine; `reply()` emits a typed, replayable sequence (`ReplyStart → ModelCall → TextBlock → ToolCall → ToolResult → ... → ReplyEnd`).

### pimono-demo layout

Entry point: `com.demo.pimono.PiMonoDemoApplication`. The whole framework fits in one screen of mental model — `AgentCore` drives the loop, `LlmClient` speaks OpenAI protocol, `ContextManager` holds one current context, `McpClientManager` ships five builtin mock tools (`get_weather`/`calculate`/`search`/`get_time`/`translate`) plus external MCP. No middleware, no events, no permission system, no sandbox — the deliberate point of comparison.

## Conventions

- **Logging**: SLF4J + Logback (`org.slf4j.Logger`). User-facing output goes through `ui/ConsoleUI` (ANSI colors via JLine3 terminal). Keep log/app-output separation clean.
- **Chinese first**: System prompts, log messages, and UI strings are predominantly Simplified Chinese. Match the surrounding file's language when adding content.
- **Tool protocol**: All tool calls (builtin or MCP) follow `{name, description, inputSchema(JSON Schema)}` and return a `tool_call_id`-keyed result. Builtin tools are registered the same way as MCP-discovered ones — there is no separate "function calling" path.
- **No Spring**: plain `public static void main`, wired by hand. Shaded uber-jars via `maven-shade-plugin`.
- **Compiler quirk**: parent POM passes `--add-exports java.base/sun.nio.ch=ALL-UNNAMED` — preserve this when touching the compiler config.
- **JUnit 5 + Mockito 5** for tests; tests live under each module's `src/test/java` mirroring the main package.

## Key files to read first when onboarding

- `ARCHITECTURE.md` — the most important doc; side-by-side comparison of both modules and the "five universal layers" abstraction.
- `AgentScope2.0剖析_20260626.md` — the upstream Python framework analysis that `agentscope-demo` re-implements.
- `agentscope-demo/src/main/java/com/demo/agentscope/AgentScopeDemoApplication.java` — wiring reference for the full stack.
- `pimono-demo/src/main/java/com/demo/pimono/agent/AgentCore.java` — 150-line Agent essence; read this first to understand what `agentscope-demo` is elaborating.

## Repo notes

- `startas.sh` / `startpm.sh` are local launcher scripts containing a personal API key — they are gitignored (`start*.sh`), so do not commit them and do not assume they exist on other machines.
- `workspace/` at the repo root is runtime scratch for the Agent (gitignored). Reports generated there are artifacts, not source.
- `.tools/` (vendored JDK + Maven) and `.codegraph/` (CodeGraph index) are gitignored — do not stage them.
- `project-documentation.html` is a generated artifact, also gitignored.
