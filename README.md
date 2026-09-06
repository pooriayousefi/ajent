
# 🧠 aJent: Agentic AI Framework

## 1. Overview

**aJent** is a dynamic, highly capable AI framework written in Clojure and C++23. It utilizes a **Unified Concurrent Orchestrator** architecture, where a single LLM instance acts as the central brain, dynamically discovering and utilizing a fleet of decoupled microservices (Tool Servers) to accomplish complex user tasks.

Built natively around the OpenAI-compatible API standard, aJent executes robust ReAct (Reason + Act) loops to solve complex problems, running tool calls in parallel across a polyglot microservice fleet while strictly managing its own context window to ensure stable, long-running sessions.

Equipped with full host machine access, a unified tool configuration system, native Model Context Protocol (MCP) support, and a human-in-the-loop security gate, aJent is designed to be a pragmatic, powerful digital worker for automating routine system operations, file manipulations, and network tasks.

---

## 2. Architectural Philosophy

The AI engineering community is currently divided between "Multi-Agent" systems (e.g., AutoGen, CrewAI) where multiple LLM personas talk to each other, and "Single-Agent" systems where one LLM orchestrates a fleet of tools.

**aJent intentionally champions the Single-Agent Concurrent Orchestrator model.** This is the industry gold standard, used by OpenAI's Assistants API and LangChain's `AgentExecutor`, because it solves the core problems of multi-agent systems:

1. **Solving the "Telephone Game":** In multi-agent systems, passing context between agents duplicates token costs and degrades performance. In aJent, the single brain holds the context, calls the tools, and synthesizes the result. Zero duplication.
2. **True Concurrency:** The Orchestrator LLM outputs multiple `tool_calls` in a single response, and aJent's Clojure backend executes the requests to different microservices in parallel. You get the performance of multi-agent systems without the complexity of inter-agent communication.
3. **Stateless Microservices:** By making the tool servers "dumb executors" that simply take arguments and return JSON, aJent achieves enterprise-grade decoupling. The tools don't know about the LLM, and the LLM doesn't know if a tool is a compiled C++ binary or a Node.js MCP subprocess.

---

## 3. Exclusive Capabilities & System Design

### Unrestricted File System Access

Unlike sandboxed frameworks, aJent is designed as a local, single-agent automation worker. It has full, unrestricted read/write access to the host machine's file system. The LLM is instructed to use exact absolute paths for all operations, empowering it to traverse directories, read logs, and write code wherever the user points it.

### Human-in-the-Loop Security Gate

Because the agent has unrestricted access, aJent implements a strict permission architecture. When the LLM attempts to perform a destructive operation (such as `delete_file`, `delete_directory`, or executing a destructive shell command like `rm` or `mkfs`), the Clojure orchestrator intercepts the request and pauses the agent loop. It prompts the user in the terminal for explicit `[y/N]` approval. The agent will not proceed until permission is granted, preventing catastrophic data loss from LLM hallucinations.

### Unified Tool Configuration (`tool_servers.json`)

aJent centralizes tool server discovery into a single, structured JSON file: `tool_servers.json`. This replaces flat text registries and allows for rich server metadata, including:

- `name` and `description`: For better logging and routing.
- `timeout_ms`: Per-server HTTP timeout configurations, preventing the agent from hanging indefinitely if a microservice crashes.
- `enabled`: Toggle servers on/off without deleting configuration entries.

### Native MCP Integration via `mcp.clj`

aJent natively supports the Model Context Protocol (MCP) through [mcp.clj](https://github.com/pooriayousefi/mcp.clj)—a pure Clojure MCP SDK (written by the aJent author) with zero Java bloat. This allows aJent to seamlessly spawn and communicate with stdio-based MCP servers (like Anthropic's official filesystem server) alongside internal C++ REST microservices. MCP tool schemas are automatically translated to the OpenAI function-calling format, making them instantly available to the Orchestrator.

### Extensible LLM Provider Registry

aJent supports a wide array of offline and online OpenAI-compatible LLM providers out of the box (local, Ollama, LM Studio, Zhipu, OpenAI, DeepSeek, Groq, Mistral, Together, OpenRouter). Providers can be deeply customized or overridden via a user-provided `providers.json` file.

### Advanced Context Management

aJent actively protects its own memory. It employs a sliding window to age out old conversation history safely, dropping leading 'tool' or 'assistant' messages to prevent OpenAI API 400 errors. It automatically caps massive tool outputs (like recursive directory listings) via a configurable `max-observation-chars` limit to prevent context overflow crashes.

---

## 4. The Tool Server API Contract

aJent is language-agnostic. Any microservice that adheres to the following HTTP/JSON contract can be used as an aJent REST Tool Server.

### Endpoint 1: Schema Discovery (Mandatory)

- **Method:** `GET /schema`
- **Response:** A JSON array of OpenAI-compatible tool schemas.

```json
[
  {
    "type": "function",
    "function": {
      "name": "tool_name",
      "description": "When to use this tool...",
      "parameters": {
        "type": "object",
        "properties": { "arg1": { "type": "string" } },
        "required": ["arg1"]
      }
    }
  }
]
```

### Endpoint 2: Tool Execution (Mandatory)

- **Method:** `POST /tools/call`
- **Request Body:**

```json
{ "name": "tool_name", "arguments": { "arg1": "value" } }
```

- **Response Body (Success):**

```json
{ "result": "Success data or string", "is_error": false }
```

- **Response Body (Error):**

```json
{ "error": "Description of the failure", "is_error": true }
```

_(Note: Logical errors should return HTTP 200 with the error payload so the LLM can read the error string and recover. Do not return HTTP 500 for expected logic failures)._

---

## 5. The Tool Server Fleet

aJent currently operates with a specialized fleet of C++23 microservices exposing high-performance native tools, alongside any MCP server you configure.

### 1. Math Server (C++ - Port 4001)

- `generate_random_number`: Generates random numbers (uniform, normal, bernoulli).
- `calculate_statistics`: Calculates mean, median, variance, standard deviation.
- `arithmetic_operation`: Performs add, subtract, multiply, divide, modulo, power.

### 2. Directory Server (C++ - Port 4002)

- `create_directory`: Creates a directory at an absolute path.
- `list_directory`: Lists immediate files/folders (non-recursive).
- `iterate_directories_recursively`: Returns a flat list of all files.
- `delete_directory`: Recursively deletes a directory. _(Requires user approval via Security Gate)_
- `get_last_modified_file`: Finds the most recently modified file in a directory.

### 3. File Server (C++ - Port 4003)

- `write_text_file`: Writes text to an absolute path.
- `read_text_file`: Reads text from a file.
- `append_text_file`: Appends text to an existing file.
- `delete_file`: Deletes a regular file. _(Requires user approval via Security Gate)_
- `file_exists`: Checks if a path exists.
- `get_file_info`: Returns metadata (size, is_directory, is_regular_file).

### 4. Shell Server (C++ - Port 4004)

- `run_command`: Executes a shell command and returns combined stdout/stderr.
- **Security:** Disabled by default. Requires the environment variable `AJENT_SHELL_ENABLED=1`. Contains a hardcoded denylist for catastrophic commands. Destructive commands are intercepted by the aJent Security Gate.

### 5. Utility Server (C++ - Port 4005)

- `tokenize_string`: Splits a string by specified delimiters.
- `count_words`: Counts exact words in a string.
- `extract_json_from_text`: Extracts the first valid JSON object/array from messy text.
- `generate_uuid`: Generates a random UUID v4 string.

### 6. MCP Servers (Any Language)

You can attach any MCP server (via `npx`, `python`, or compiled binaries) to the fleet. For example, attaching the official `@modelcontextprotocol/server-filesystem` instantly grants aJent 13 additional tools (`read_text_file`, `directory_tree`, `search_files`, etc.) managed via stdio.

---

## 6. Framework Configuration

### `tool_servers.json`

Located in the project root directory. It acts as the single source of truth for the tool fleet, managing both REST and MCP transports.

```json
{
  "rest_servers": [
    {
      "name": "math",
      "url": "http://localhost:4001",
      "enabled": true,
      "timeout_ms": 5000,
      "description": "Arithmetic, statistics and random-number tools"
    }
  ],
  "mcp_servers": [
    {
      "name": "mcp-filesystem",
      "transport": "stdio",
      "command": [
        "npx",
        "-y",
        "@modelcontextprotocol/server-filesystem",
        "/tmp"
      ],
      "enabled": true,
      "description": "Official Anthropic MCP Filesystem Server"
    }
  ]
}
```

### `providers.json` (Optional)

Allows overriding built-in LLM provider definitions or adding custom OpenAI-compatible gateways.

```json
{
  "my-company-gateway": {
    "description": "Internal OpenAI-compatible gateway",
    "base-url": "https://gateway.internal:8443/v1",
    "api-key-env": "GATEWAY_API_KEY",
    "default-model": "gpt-4o-mini",
    "max-iterations": 30
  }
}
```

---

## 7. Execution & Usage

### Step 1: Start the Tool Server Fleet

Compile the C++ tool servers and execute them. Each server requires `<host>` and `<port>` arguments.

```bash
./math_server 127.0.0.1 4001 &
./directory_server 127.0.0.1 4002 &
./file_server 127.0.0.1 4003 &
./shell_server 127.0.0.1 4004 &
./utility_server 127.0.0.1 4005 &
```

_(Note: If you configured MCP servers like `npx` in `tool_servers.json`, aJent will spawn them automatically)._

### Step 2: Start the LLM Provider

aJent requires an OpenAI-compatible API. For local execution, `llama.cpp` is highly recommended. Allocate a large context window (`-c 16384`) and restrict the server to a single parallel slot (`-np 1`).

```bash
llama-server --jinja -m ~/llms/Qwen2.5-32B-Instruct-Q4_K_M.gguf \
  --n-gpu-layers 999 --parallel 1 --cont-batching \
  --ctx-size 16384 --batch-size 1024 --flash-attn on \
  --host 127.0.0.1 --port 8080
```

### Step 3: Run aJent

Run the framework using `lein` or by building an uberjar.

**Via Leiningen:**

```bash
lein run local http://localhost:8080 gpt-oss-20b 0.7
```

**Via Uberjar:**

```bash
lein uberjar
java -jar target/ajent.jar local http://localhost:8080 gpt-oss-20b 0.7
```

You will be greeted with the `aJent>` prompt. Type your prompts and press Enter. Type `exit` or `quit` to stop.

---

## 8. Advanced Orchestrator Capabilities

The Orchestrator's system prompt enforces advanced Agentic behaviors:

1. **Concurrent Execution:** If the user requests multiple independent tasks, the LLM emits multiple tool calls in one response. The framework executes them concurrently in parallel.
2. **Error Recovery:** If a tool returns an error, the LLM is instructed NOT to report failure immediately, but to analyze the error, adjust its arguments, and try again.
3. **Large Content Chunking:** The system prompt explicitly directs the LLM to avoid generating massive text strings in a single tool call, instead using file appending tools to build large documents incrementally.
4. **Contextual Resolution:** The Orchestrator holds the full conversation history. If the user says "read that file", the LLM resolves "that file" from the context window before calling a tool.

---

## 9. Release Notes

### aJent v2.0.0

**Features:**

- Unified Concurrent Orchestrator with non-streaming ReAct loop.
- **Native Model Context Protocol (MCP) support** via the pure Clojure `mcp.clj` library.
- Replaced flat text registries with unified `tool_servers.json` for REST and MCP tool fleets.
- Unrestricted file system access for native host automation.
- Human-in-the-loop Security Gate intercepting all destructive file and shell operations.
- Per-server HTTP timeout configurations to prevent agent deadlocks.
- Robust sliding context window and output truncation to prevent LLM context crashes.
- Extensible LLM provider registry supporting offline and online OpenAI-compatible backends via `providers.json`.

**Prerequisites for running:**

- **Java 21+** installed (Clojure 1.12 requires a modern Java).
- The **Tool Servers** running and configured in `tool_servers.json` (C++ binaries / MCP subprocesses).
- A local **LLM server** (like `llama-server`) or an online API key.

---

## Acknowledgements

I would like to express my gratitude to my AI assistant and mentor, **GLM-5.2**, for their exceptional consultancy and collaborative support throughout the development of this project. Your guidance was invaluable in bringing this framework to life.

## License

Copyright © 2026 Pooria Yousefi

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
<https://www.eclipse.org/legal/epl-2.0/>.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at <https://www.gnu.org/software/classpath/license.html>.
