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

The MCP client, protocol, and OpenAI tool adapter from [mcp.clj](https://github.com/pooriayousefi/mcp.clj) are integrated under `src/mcp/`. This keeps the reusable MCP library independently maintained while making this aJent branch self-contained; the vendored namespaces are pinned to mcp.clj commit `6eb31df`.

## Requirements

- Java 17+
- Leiningen
- An OpenAI-compatible model endpoint
- MCP servers implementing the supported MCP 2026-07-28 tools surface

## Build

The MCP transport uses `http-kit`; the MCP client/protocol/adapter namespaces are vendored from mcp.clj.

```sh
lein deps
lein test
lein uberjar
```

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

The repository includes a starter mcp_servers.json with disabled examples. Replace the example command and URL with your own MCP servers before enabling them.

### Existing REST tool servers

The previous aJent tool-server contract (GET /schema and POST /tools/call) is no longer consumed by aJent. Those servers must be migrated to MCP or wrapped by an MCP server. An ordinary HTTP URL is not automatically an MCP endpoint.

The old tool_servers.json is retained only as a deprecation notice; it is not loaded.

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

Provider definitions and overrides remain in src/ajent/providers.clj and optional providers.json. The LLM API remains OpenAI-compatible; only the tool integration has moved from the proprietary REST tool-server contract to MCP.

The usual provider environment variables remain supported, with AJENT_API_KEY as a fallback.

## Version

aJent 2.2.0 — MCP-native tool orchestration.

## License

Copyright © 2026 Pooria Yousefi. Distributed under the Eclipse Public License 2.0.
