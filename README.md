
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

## LLM execution modes

aJent has two explicit entry points. Both use the same ReAct agent, MCP tool registry, and conversation loop; only the model provider configuration differs.

### Local model (no API key)

Connect to any local OpenAI-compatible endpoint, such as llama.cpp, Ollama, or LM Studio:

```sh
lein run local http://127.0.0.1:8080 gpt-oss-20b 0.7
```

The host URL is normalized to an OpenAI-compatible API base URL (a trailing `/v1` is added when needed). Local mode does not attach an Authorization header.

### Hosted provider (API key required)

Choose a built-in online provider and supply its key through the provider-specific environment variable:

```sh
export OPENAI_API_KEY="..."
lein run online openai gpt-4o 0.7

export DEEPSEEK_API_KEY="..."
lein run online deepseek deepseek-chat 0.7

export OPENROUTER_API_KEY="..."
lein run online openrouter openai/gpt-4o 0.7
```

If the provider-specific variable is not set, aJent checks `AJENT_API_KEY` as a universal fallback. Online mode rejects missing keys and providers classified as local. Never put API keys directly in command-line arguments.

Built-in hosted providers include OpenAI, Zhipu, DeepSeek, Groq, Mistral, Together AI, and OpenRouter. Custom entries in `providers.json` can be used in online mode by specifying `api-key-env` (and optionally `mode: "online"`).

Use `lein run --help` to see the supported syntax and provider list.

## Configure MCP servers

Create or edit mcp_servers.json in the working directory. Each entry must define a unique name and exactly one transport:

- command: argument vector used to launch a local stdio MCP server.
- url: absolute Streamable HTTP MCP endpoint URL.

```json
{
  "mcp_servers": [
    {
      "name": "local-files",
      "command": ["clojure", "-M", "-m", "my.tools.files"],
      "cwd": "/absolute/path/to/my-mcp-server",
      "enabled": true,
      "timeout_ms": 30000
    },
    {
      "name": "remote-tools",
      "url": "http://127.0.0.1:3000/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN"
      },
      "enabled": true,
      "timeout_ms": 30000
    }
  ]
}
```

For stdio servers, cwd and env are optional. For HTTP servers, headers may contain authentication headers. Set enabled to false to disable an entry. The default request timeout is 30 seconds.

The repository ships an empty MCP server list. Add the command or URL of each
MCP server you want to use. With an empty list, aJent runs as model-only chat.

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

Use [tools/mcp_servers.example.json](tools/mcp_servers.example.json) as a template
for your root `mcp_servers.json`. If you already have entries, merge the ones
you want to use. Start aJent from the repository root so the relative binary
paths resolve. The example leaves the shell server disabled. The memory server
stores `sauron_memory.json` in the working directory.

## Run

Start your model endpoint. For example, with llama.cpp:

```sh
llama-server --jinja -m ~/llms/model.gguf \
  --n-gpu-layers 999 --ctx-size 16384 \
  --host 127.0.0.1 --port 8080
```

Then launch aJent:

```sh
lein run local http://localhost:8080 model-name 0.7
```

Or build and run the uberjar:

```sh
java -jar target/ajent.jar local http://localhost:8080 model-name 0.7
```

## Tool execution flow

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

## Configuration

Built-in model providers live in `src/ajent/providers.clj`. Create an optional
local `providers.json` only when you need to add or override a provider. The
file is ignored by Git. aJent connects to the model endpoint you supply.
All tool discovery and execution use MCP, over stdio or Streamable HTTP.

The usual provider environment variables remain supported, with AJENT_API_KEY as a fallback. Select `local` or `online` explicitly as described above.

## Version

aJent 2.2.0 — MCP-native tool orchestration.

## License

Copyright © 2026 Pooria Yousefi. Distributed under the Eclipse Public License 2.0.
