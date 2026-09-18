<img width="1672" height="941" alt="7c38f039-e2a7-4230-8b90-126e9fa95cf4" src="https://github.com/user-attachments/assets/49468897-7d8b-43c1-9398-9dbf2dd9164d" />


# 🧠 aJent: Agentic AI Framework
[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.pooriayousefi/ajent.svg)](https://clojars.org/org.clojars.pooriayousefi/ajent)


## 1. Overview

**aJent** is a dynamic, highly capable AI framework written in Clojure. It utilizes a **Unified Concurrent Orchestrator** architecture, where a single LLM instance acts as the central brain, dynamically discovering and utilizing a fleet of decoupled RESTful microservices (Tool Servers) to accomplish complex user tasks.

Built natively around the OpenAI-compatible API standard, aJent executes robust ReAct (Reason + Act) loops to solve complex problems, running tool calls in parallel across a polyglot microservice fleet while strictly managing its own context window to ensure stable, long-running sessions.

Equipped with full host machine access, a unified tool configuration system, a Dockerized Python-as-a-Service code interpreter, and a human-in-the-loop security gate, aJent is designed to be a pragmatic, powerful digital worker for automating routine system operations, data analysis, document generation, and network tasks.

---

## 2. Architectural Philosophy

**aJent intentionally champions the Single-Agent Concurrent Orchestrator model.** This is the industry gold standard, used by OpenAI's Assistants API and LangChain's `AgentExecutor`, because it solves the core problems of multi-agent systems:

1. **Solving the "Telephone Game":** In multi-agent systems, passing context between agents duplicates token costs and degrades performance. In aJent, the single brain holds the context, calls the tools, and synthesizes the result. Zero duplication.
2. **True Concurrency:** The Orchestrator LLM outputs multiple `tool_calls` in a single response, and aJent's Clojure backend executes the requests to different microservices in parallel using `pmap`. You get the performance of multi-agent systems without the complexity of inter-agent communication.
3. **Stateless Microservices:** By making the tool servers "dumb executors" that simply take arguments and return JSON, aJent achieves enterprise-grade decoupling. The tools don't know about the LLM, and the LLM doesn't know if a tool is a compiled C++ binary, a Clojure REST server, or a Dockerized Python environment.

---

## 3. Exclusive Capabilities & System Design

### Python-as-a-Service (Code Interpreter)
aJent's most powerful capability is its integration with a Dockerized Python REST server. Instead of writing rigid REST wrappers for every conceivable data science or document generation library, aJent includes an `execute_python` tool. 
*   **Infinite Toolset:** The LLM writes Python code natively and sends it to the REST server.
*   **Sandboxed Security:** The Python execution environment runs inside a Docker container, isolating host system resources.
*   **Pre-installed Powerhouse:** The container comes pre-loaded with `numpy`, `pandas`, `scipy`, `matplotlib`, `pymupdf` (PDF), `python-docx` (Word), `python-pptx` (PowerPoint), and `openpyxl` (Excel).
*   **Persian/RTL Support:** Includes `arabic-reshaper`, `python-bidi`, and pre-installed fonts (Vazirmatn, B Nazanin) for flawless right-to-left PDF and image generation.
*   **File Mapping:** Generated files are mapped directly to the host machine via Docker volumes (`/app/output`), allowing the user instant access to AI-generated documents.

### Unrestricted File System Access
Unlike sandboxed frameworks, aJent is designed as a local, single-agent automation worker. It has full, unrestricted read/write access to the host machine's file system. The LLM is instructed to use exact absolute paths for all operations, empowering it to traverse directories, read logs, and write code wherever the user points it.

### Human-in-the-Loop Security Gate
Because the agent has unrestricted access, aJent implements a strict permission architecture. When the LLM attempts to perform a destructive operation (such as `delete_file`, `delete_directory`, or executing a destructive shell command like `rm` or `mkfs`), the Clojure orchestrator intercepts the request and pauses the agent loop. It prompts the user in the terminal for explicit `[y/N]` approval. The agent will not proceed until permission is granted, preventing catastrophic data loss from LLM hallucinations.

### Unified Tool Configuration (`tool_servers.json`)
aJent centralizes tool server discovery into a single, structured JSON file: `tool_servers.json`. This replaces flat text registries and allows for rich server metadata, including:
- `name` and `description`: For better logging and routing.
- `timeout_ms`: Per-server HTTP timeout configurations, preventing the agent from hanging indefinitely if a microservice crashes.
- `enabled`: Toggle servers on/off without deleting configuration entries.

### Advanced Context Management
aJent actively protects its own memory. It employs a sliding window to age out old conversation history safely, dropping leading 'tool' or 'assistant' messages to prevent OpenAI API 400 errors. It automatically caps massive tool outputs (like recursive directory listings) via a configurable `max-observation-chars` limit to prevent context overflow crashes.

---

## 4. The Tool Server API Contract

aJent is language-agnostic. Any microservice that adheres to the following HTTP/JSON contract can be used as an aJent REST Tool Server.

### Endpoint 1: Schema Discovery (Mandatory)
- **Method:** `GET /schema`
- **Response:** A JSON array of OpenAI-compatible tool schemas.

### Endpoint 2: Tool Execution (Mandatory)
- **Method:** `POST /tools/call`
- **Request Body:** `{ "name": "tool_name", "arguments": { "arg1": "value" } }`
- **Response Body:** `{ "result": "Success data or string", "is_error": false }` or `{ "error": "Description of the failure", "is_error": true }`

_(Note: Logical errors should return HTTP 200 with the error payload so the LLM can read the error string and recover. Do not return HTTP 500 for expected logic failures)._

---

## 5. The Tool Server Fleet

aJent operates with a specialized fleet of REST microservices exposing high-performance native tools:

1. **Python Runtime Server (Docker - Port 4007):** Executes Python 3 code. Pre-installed with data science, document generation, and Persian RTL libraries.
2. **Math Server (C++ - Port 4001):** Arithmetic, statistics, and random-number generation.
3. **Directory Server (C++ - Port 4002):** Directory creation, listing, recursive iteration, and deletion.
4. **File Server (C++ - Port 4003):** File reading, writing, appending, copying, and metadata retrieval.
5. **Shell Server (C++ - Port 4004):** Shell command execution with security gates.
6. **Utility Server (C++ - Port 4005):** String tokenization, word counting, JSON extraction, and UUID generation.
7. **Time Server (Clojure - Port 4006):** UTC/Local time and Unix timestamp retrieval.

---

## 6. How To Build the Servers

aJent's tool servers are located in the `servers/` directory. To run aJent, you must start these servers first. 

### Option A: Python Runtime Server (Docker required)
```bash
cd servers/python-runtime
docker build -t ajent-python-server .
docker run -d -p 4007:4007 -v ~/Documents/ajent_files:/app/output --name ajent-python ajent-python-server
cd ../..
```

### Option B: Time Server (Clojure)
The pre-built JAR is included in the repository. You can run it directly:
```bash
cd servers/time-server
java -jar target/time-server.jar &
cd ../..
```
*(If you prefer to build from source, run `lein uberjar` inside the `time-server` directory).*

### Option C: C++ Servers (Math, File, Directory, Shell, Utility)
The `servers/` directory contains a `CMakeLists.txt` file and the `include/` folder with `cpp-httplib` and `nlohmann/json`. 

**On Linux / macOS:**
```bash
cd servers
mkdir build && cd build
cmake ..
make
# Run the compiled binaries
./math_server 127.0.0.1 4001 &
./directory_server 127.0.0.1 4002 &
./file_server 127.0.0.1 4003 &
./shell_server 127.0.0.1 4004 &
./utility_server 127.0.0.1 4005 &
cd ../..
```

**On Windows (using CMake and Visual Studio):**
```cmd
cd servers
mkdir build
cd build
cmake ..
cmake --build . --config Release
:: Run the compiled executables
Release\math_server.exe 127.0.0.1 4001
Release\directory_server.exe 127.0.0.1 4002
Release\file_server.exe 127.0.0.1 4003
Release\shell_server.exe 127.0.0.1 4004
Release\utility_server.exe 127.0.0.1 4005
cd ..\..
```

---

## 7. Execution & Usage

### Step 1: Start the LLM Provider
aJent requires an OpenAI-compatible API. For local execution, `llama.cpp` is highly recommended.
```bash
llama-server --jinja -m ~/llms/gpt-oss-20b.gguf \
  --n-gpu-layers 999 --parallel 1 --cont-batching \
  --ctx-size 16384 --batch-size 1024 --flash-attn on \
  --host 127.0.0.1 --port 8080
```

### Step 2: Run aJent
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

## 8. Framework Configuration

### `tool_servers.json`
Located in the project root directory. It acts as the single source of truth for the REST tool fleet.

```json
{
  "rest_servers": [
    {
      "name": "python-runtime",
      "description": "Dockerized Python 3 environment.",
      "url": "http://127.0.0.1:4007",
      "enabled": true,
      "timeout_ms": 60000
    },
    {
      "name": "math",
      "url": "http://localhost:4001",
      "enabled": true,
      "timeout_ms": 5000,
      "description": "Arithmetic, statistics and random-number tools"
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

## 9. Advanced Orchestrator Capabilities

1. **Concurrent Execution:** If the user requests multiple independent tasks, the LLM emits multiple tool calls in one response. The framework executes them concurrently in parallel.
2. **Error Recovery:** If a tool returns an error, the LLM is instructed NOT to report failure immediately, but to analyze the error, adjust its arguments, and try again.
3. **Large Content Chunking:** The system prompt explicitly directs the LLM to avoid generating massive text strings in a single tool call, instead using file appending tools or Python scripts to build large documents incrementally.
4. **Contextual Resolution:** The Orchestrator holds the full conversation history. If the user says "read that file", the LLM resolves "that file" from the context window before calling a tool.
5. **Python File I/O:** When using the `execute_python` tool, the LLM is strictly instructed to save generated files to the `/app/output` directory, ensuring the user can access them on the host machine.

---

## 10. Release Notes

### aJent v2.1.0
- Unified Concurrent Orchestrator with non-streaming ReAct loop.
- **Python-as-a-Service Integration:** Dockerized Python 3 code interpreter with pre-installed data science, document generation, and Persian RTL support.
- Strictly RESTful tool architecture (MCP mechanisms fully removed for standardization).
- Unrestricted file system access for native host automation.
- Human-in-the-loop Security Gate intercepting all destructive file and shell operations.
- Monorepo structure: Tool servers included in `servers/` directory with CMake and Docker support.

**Prerequisites:**
- **Java 21+** installed.
- **Docker** (if utilizing the Python-as-a-Service tool server).
- **CMake** and a C++23 compiler (if building C++ tool servers from source).
- A local **LLM server** or an online API key.

---

## Acknowledgements
I would like to express my gratitude to my AI assistant and mentor, **GLM-5.2**, for their exceptional consultancy and collaborative support throughout the development of this project.

## License
Copyright © 2026 Pooria Yousefi
Distributed under the Eclipse Public License 2.0.

---
