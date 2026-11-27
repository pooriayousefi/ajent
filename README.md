
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.pooriayousefi/ajent.svg)](https://clojars.org/org.clojars.pooriayousefi/ajent)


# aJent: MCP-native Agentic AI Framework

aJent is a Clojure single-agent runtime that uses an OpenAI-compatible Chat Completions API as its reasoning interface and **Model Context Protocol (MCP)** as its native tool interface.

The model remains the central orchestrator. aJent discovers tools from multiple MCP servers, converts their JSON Schemas into OpenAI-compatible function tools, executes returned tool calls concurrently, and appends the observations to the conversation.

## Architecture

- **LLM provider:** OpenAI-compatible REST API (local or hosted).
- **Tool protocol:** MCP 2026-07-28.
- **Local tools:** MCP servers launched as child processes over stdio.
- **Remote tools:** MCP servers accessed over Streamable HTTP.
- **Orchestration:** Single-agent ReAct loop with concurrent tool execution.
- **Safety:** aJent's human approval gate runs before destructive tool calls are dispatched.
- **Context:** Sliding history window and configurable observation truncation.

aJent uses [mcp.clj](https://github.com/pooriayousefi/mcp.clj) for MCP transport, protocol handling, and conversion to OpenAI-compatible tool schemas.

## Requirements

- Java 17+
- Leiningen
- An OpenAI-compatible model endpoint
- MCP servers implementing the supported MCP 2026-07-28 tools surface

## Build

Leiningen resolves the published `mcp.clj` dependency from Clojars.

```sh
lein deps
lein test
lein uberjar
```

## Configuration

aJent reads configuration relative to the working directory. The filenames,
JSON structures, and launch flags follow SAURON.

| File | Contents |
| --- | --- |
| `cfg/ai_providers.json` | A `providers` array with provider names and model entries. Each model has a `model-name` and a full OpenAI-compatible `base-url`. Set `temp` and `max-tokens` per model. |
| `cfg/mcp_servers.json` | An `mcp_servers` array with one uniquely named entry per server and settings for both transports. |
| `cfg/system_prompts.json` | An object mapping persona names to system prompts. The default persona is `orchestrator`. |

A hosted provider or model can name its API key environment variable with
`api-key-env`. Model settings take precedence over provider settings. If the
named variable is unset, aJent checks `AJENT_API_KEY`. Store the key itself in
the environment.

### MCP transports

```json
{
  "mcp_servers": [
    {
      "name": "time",
      "transports": {
        "stdio": {
          "enable": true,
          "command": "./tools/time-mcp-server/bin/time_mcp_tool_server",
          "args": [],
          "env": {}
        },
        "http": {
          "enable": false,
          "host": "127.0.0.1",
          "port": 8091
        }
      }
    }
  ]
}
```

- **Stdio only** launches the configured command with `stdio` followed by
  `args`, and passes `env` to the child process.
- **HTTP only** connects to an already-running server at `http://HOST:PORT/mcp`.
- **Both** launches the command with `both HOST PORT` followed by `args`.
  aJent communicates with the child over stdio.
- **Both disabled** skips the server. With no enabled servers, aJent runs as
  model-only chat. An enabled server that fails startup or discovery stops
  startup with an error naming the server.

### Migration

Move custom MCP entries into `cfg/mcp_servers.json`. The first element of
a legacy command array becomes `transports.stdio.command`, any remaining
elements become `args`, and `env` moves under `stdio`. A standard HTTP URL
becomes `transports.http.host` and `port`. For custom URLs or auth headers,
aJent still accepts legacy flat entries. It reads the root
`mcp_servers.json` only when the file under `cfg/` is absent.

The previous positional `local` and `online` commands remain available for
compatibility. They still use the old provider defaults and optional root
`providers.json` overrides. Move custom models into `cfg/ai_providers.json`
to select them with `--provider` and `--model`.

## Bundled C++ MCP tool servers

The `tools/` directory pins the standalone directory, file, Git, memory,
random-number, shell, statistics, time, and utility servers as Git submodules.
Their nested `mcpxx` submodules supply the C++ MCP implementation.

From the repository root, initialize the sources and build the servers with
Make and a C++20 compiler:

```sh
git submodule update --init --recursive
make -C tools
```

The checked-in `cfg/mcp_servers.json` enables all nine servers over stdio.
Start aJent from the repository root after building them so the binary paths
resolve. For model-only chat, set `mcp_servers` to an empty array. To
restore the bundled settings, use
[tools/mcp_servers.example.json](tools/mcp_servers.example.json).
The shell entry sets `MCPXX_SHELL_ENABLED=1` in `stdio.env`. The memory
server stores `sauron_memory.json` in the working directory.

## Run

Start your model endpoint. For example, with llama.cpp:

```sh
llama-server --jinja -m ~/llms/model.gguf \
  --n-gpu-layers 999 --ctx-size 16384 \
  --host 127.0.0.1 --port 8080
```

Set the model name and endpoint in `cfg/ai_providers.json`, then launch aJent:

```sh
lein run --agent orchestrator --provider local --model gpt-oss-20b
```

Or build and run the uberjar:

```sh
java -jar target/ajent.jar --agent orchestrator --provider local --model gpt-oss-20b
```

## Tool execution flow

The console shows progress while waiting for a model response and while
approved MCP tool calls run. Model responses are still delivered in full.

1. aJent connects to each enabled MCP server.
2. MCP tools are discovered with tools/list, including paginated results.
3. mcp.openai/tools creates OpenAI-compatible function schemas and collision-safe namespaced routes.
4. The schemas are sent to the configured model endpoint.
5. The model returns one or more tool calls.
6. aJent resolves each exposed function name to its original MCP tool. Destructive operations are presented for user approval before execution.
7. Approved calls execute concurrently; results are returned as matching tool messages using the original tool_call_id.
8. The ReAct loop continues until the model returns a final answer or reaches its iteration limit.

## Security notes

MCP does not itself make a tool safe. A connected server can have the permissions of its own process or service account. Review each server before enabling it, keep remote endpoints protected with authentication and network controls, and use least-privilege operating-system accounts.

The aJent approval gate is a defense-in-depth check for known destructive tool names and shell commands. It is not a general sandbox and cannot infer every destructive operation exposed by arbitrary third-party MCP servers. Treat tools from untrusted servers as untrusted code with potentially powerful capabilities.

## Version

aJent 2.2.0 — MCP-native tool orchestration.

## License

Copyright © 2026 Pooria Yousefi. Distributed under the Eclipse Public License 2.0.
