# aJent

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.pooriayousefi/ajent.svg)](https://clojars.org/org.clojars.pooriayousefi/ajent)

aJent is a Clojure/JVM agent runtime that connects an OpenAI-compatible Chat
Completions endpoint to Model Context Protocol (MCP) tools. Select a persona,
provider, and model at startup. aJent discovers tools from the configured
servers, sends their schemas to the model, and returns tool results to the
model until it answers or reaches the turn limit.

## Quick start

You need Java 17 or newer, Leiningen, Git, Make, and a C++20 compiler for the
bundled servers. Start an OpenAI-compatible model server separately. The
default local model entry expects `gpt-oss-20b` at `http://127.0.0.1:8080/v1`.
Adjust the model name and endpoint in `cfg/ai_providers.json` to match your server.

```sh
git clone --recurse-submodules https://github.com/pooriayousefi/ajent.git
cd ajent
make -C tools
lein test
lein run --agent orchestrator --provider local --model gpt-oss-20b
```

For an existing checkout, initialize the nested submodules with
`git submodule update --init --recursive` before building the tools. Run
aJent from the repository root so the `cfg/` files and relative server
paths resolve. Type `exit` or `quit` to leave the console.

## How a turn works

1. aJent loads the selected system prompt and model settings, starts or
   connects to the enabled MCP servers, and discovers their tools.
2. It sends the prompt, conversation history, and tool schemas to the
   configured model's `/chat/completions` endpoint.
3. If the model requests tools, aJent resolves each tool to its server. It
   checks whether the call requires approval before dispatching approved calls
   concurrently.
4. It returns each result with the matching tool-call ID and asks the model
   to continue. A final response or the iteration limit ends the turn.

The console shows when aJent is waiting for the model, preparing and running
tool calls, and receiving observations. Chat Completions responses are
non-streaming. aJent applies a sliding history window and limits the size of
tool observations retained in context.

The [mcp.clj](https://github.com/pooriayousefi/mcp.clj) dependency handles MCP
transport, discovery, and the OpenAI-compatible tool adapter. The native
servers under `tools/` are pinned Git submodules and use
[mcpxx](https://github.com/pooriayousefi/mcpxx).

## Configuration

aJent follows the [SAURON](https://github.com/pooriayousefi/sauron) file layout
and startup flags. Configuration paths are relative to the working directory.

| File | What to configure |
| --- | --- |
| [`cfg/ai_providers.json`](cfg/ai_providers.json) | Provider names, models, endpoint URLs, and optional API key environment variables |
| [`cfg/system_prompts.json`](cfg/system_prompts.json) | Persona names mapped to system prompt strings |
| [`cfg/mcp_servers.json`](cfg/mcp_servers.json) | MCP server names and stdio and HTTP transport settings |

### Providers and personas

Each entry in the `providers` array contains a `name` and a `models` array.
Each model has a `model-name` and a full API `base-url`, including any
version path. Set `temp` and `max-tokens` on the model entry as needed.
For authenticated endpoints, set `api-key-env` on the provider or model
and export that variable before launch. The model's setting takes precedence.
aJent checks `AJENT_API_KEY` if the named environment variable is unset.

Use `--provider NAME` and `--model NAME` to select the configured pair.
Add a named prompt to `cfg/system_prompts.json` and select it with
`--agent NAME`. The default is `orchestrator`. A persona changes the system
prompt and uses the same configured tool registry.

Built-in personas are `orchestrator`, `cybersentinel` (defensive security),
`system-observer`, `resume-specialist`, `education-advisor`, `code-reviewer`,
`qa-engineer`, `project-planner`, and `git-specialist`.

```sh
lein run --agent code-reviewer --provider local --model gpt-oss-20b
```

### MCP servers

Each entry in `mcp_servers` has a unique `name` and two transport sections.
Each section has a boolean `enable` field.

| Enabled transports | Behavior |
| --- | --- |
| Stdio | Launches `command stdio` followed by `args`. Passes `env` to the child. |
| HTTP | Connects to an existing Streamable HTTP server at `http://HOST:PORT/mcp`. |
| Stdio and HTTP | Launches `command both HOST PORT` followed by `args` and communicates through stdio. |
| Neither | Skips the entry. |

The default configuration starts nine servers over stdio and exposes 99 tools
across file, directory, shell, time, Git, random-number, statistics, memory,
and utility operations. An enabled server that fails to start or discover
tools produces a startup error naming that server. Set `mcp_servers` to
`[]` for model-only chat.

The shell entry sets `MCPXX_SHELL_ENABLED=1` under `transports.stdio.env`.
Set both transport flags to `false` to skip the shell server. Tool operations
run with the server process's permissions. The interactive approval check
covers known destructive file operations and shell command patterns.

### Upgrading an older checkout

Keep a copy of any customized settings before updating. Move server entries
to `cfg/mcp_servers.json`. For a legacy stdio command array, move its first
element to `transports.stdio.command` and the rest to `args` under the same
section. Move `env` there as well. For a standard HTTP endpoint, put its host
and port under `transports.http`. Legacy flat entries still work for custom
URLs or headers when placed in `cfg/mcp_servers.json`.

The old positional `local` and `online` commands still work with the legacy
provider registry and optional root `providers.json`. Move custom models to
`cfg/ai_providers.json` to select them with the current CLI.

## Standalone JAR and library dependency

```sh
lein uberjar
java -jar target/ajent.jar --version
java -jar target/ajent.jar --provider local --model gpt-oss-20b
```

The standalone JAR includes JVM dependencies. It reads the external `cfg/`
files and launches the compiled native tools from their configured paths.

Once a version is published, add it to another Leiningen project's
`:dependencies` using the version listed on
[Clojars](https://clojars.org/org.clojars.pooriayousefi/ajent). For aJent 2.3.0:

```clojure
[org.clojars.pooriayousefi/ajent "2.3.0"]
```

The Clojars artifact is the library JAR. Build the standalone JAR with
`lein uberjar` when you want to run aJent directly with Java.

## License

Copyright © 2026 Pooria Yousefi. Distributed under the Eclipse Public License 2.0.
