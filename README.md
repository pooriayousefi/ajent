# 🧠 aJent: Agentic AI Framework

## 1. Overview

**aJent** is a dynamic, highly capable AI framework written in Clojure. It utilizes a **Unified Concurrent Orchestrator** architecture, where a single LLM instance acts as the central brain, dynamically discovering and utilizing a fleet of decoupled RESTful microservices (Tool Servers) to accomplish complex user tasks.

Built natively around the OpenAI-compatible API standard, aJent executes robust ReAct (Reason + Act) loops to solve complex problems, running tool calls in parallel across a polyglot microservice fleet (C++, Clojure, Python) while strictly managing its own context window to ensure stable, long-running sessions.

---

## 2. Architectural Philosophy

The AI engineering community is currently divided between "Multi-Agent" systems (e.g., AutoGen, CrewAI) where multiple LLM personas talk to each other, and "Single-Agent" systems where one LLM orchestrates a fleet of tools.

**aJent intentionally champions the Single-Agent Concurrent Orchestrator model.** This is the industry gold standard, used by OpenAI's Assistants API and LangChain's `AgentExecutor`, because it solves the core problems of multi-agent systems:

1. **Solving the "Telephone Game":** In multi-agent systems, passing context between agents duplicates token costs and degrades performance. In aJent, the single brain holds the context, calls the tools, and synthesizes the result. Zero duplication.
2. **True Concurrency:** The Orchestrator LLM outputs multiple `tool_calls` in a single response, and aJent's Clojure backend executes the HTTP requests to different microservices in parallel. You get the performance of multi-agent systems without the complexity of inter-agent communication.
3. **Stateless Microservices:** By making the tool servers "dumb executors" that simply take arguments and return JSON, aJent achieves enterprise-grade decoupling. The tools don't know about the LLM, and the LLM doesn't know if a tool is written in C++, Python, or Clojure.

---

## 3. Pros & Cons of the aJent Architecture

### Strengths

- **Extreme Stability:** By utilizing a structured, non-streaming ReAct loop, the framework ensures that tool calls are processed as complete, validated JSON payloads, eliminating fragility around partial JSON parsing.
- **Context Window Management:** aJent actively protects its own memory. It employs a sliding window to age out old conversation history safely, and automatically caps massive tool outputs (like recursive directory listings) to prevent context overflow crashes.
- **Idiomatic Concurrency:** Built natively on Clojure's parallel processing capabilities, the framework can execute multiple tool calls simultaneously across your thread pool without requiring complex asynchronous state machines.
- **Dynamic Agentic Workspaces:** The framework dynamically detects the user's home directory and injects a workspace path (`~/aJentWorkbench`) into the LLM's system prompt at runtime. Tool servers contain zero hardcoded paths, ensuring cross-platform file safety.
- **Language Agnostic Tooling:** Any microservice that adheres to the simple HTTP/JSON contract can be used as a tool server, allowing developers to write tools in whatever language best suits the task.
- **Advanced Error Recovery:** The Orchestrator is instructed to analyze tool error messages, adjust its arguments, and retry failing operations rather than halting execution.

### Weaknesses

- **Context Window Limits:** Like all single-agent architectures, if an agent must read a massive file (e.g., a 500-page book) and summarize it, it will eventually run out of physical memory. (This requires external Map-Reduce chunking or RAG strategies not natively built into the orchestrator).
- **Strict JSON Reliance:** The framework's success is highly dependent on the underlying LLM's ability to adhere to strict JSON tool-call schemas. Less capable models will struggle to generate valid tool calls.
- **Synchronous UX:** Because the framework waits for the full LLM response to ensure structural JSON integrity before executing tools, the user does not experience a live "typewriter" streaming effect during the reasoning phase.
- **Not a Multi-Agent System:** aJent lacks inter-agent debate or persona conflict resolution. It executes tasks sequentially as a single, unified brain rather than crowdsourcing decisions among simulated personas.

---

## 4. The Tool Server API Contract

aJent is language-agnostic. Any microservice that adheres to the following HTTP/JSON contract can be used as an aJent Tool Server.

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

aJent currently operates with 7 specialized microservices across 3 programming languages (C++, Clojure, Python), exposing **24 unique tools**.

### 1. DirServer (C++ - Port 4001)

- `create_directory`: Creates a directory at an absolute path.
- `list_directory`: Lists immediate files/folders (non-recursive).
- `iterate_directories_recursively`: Returns a flat list of all files.
- `delete_directory`: Recursively deletes a directory.
- `get_last_modified_file`: Finds the most recently modified file in a directory.

### 2. FileServer (C++ - Port 4002)

- `write_text_file`: Writes text to an absolute path.
- `read_text_file`: Reads text from a file.
- `append_text_file`: Appends text to an existing file.
- `delete_file`: Deletes a regular file.
- `file_exists`: Checks if a path exists.
- `get_file_info`: Returns metadata (size, is_directory, is_regular_file).

### 3. MathServer (C++ - Port 4003)

- `generate_random_number`: Generates random numbers (uniform, normal, bernoulli).
- `calculate_statistics`: Calculates mean, median, variance, standard deviation.
- `arithmetic_operation`: Performs add, subtract, multiply, divide, modulo, power.

### 4. ShellServer (C++ - Port 4004)

- `run_command`: Executes a shell command and returns combined stdout/stderr.
- **Security:** Disabled by default. Requires `AJENT_SHELL_ENABLED=1`.

### 5. TimeServer (Clojure - Port 4005)

- `get_current_utc_date_time`: Returns current UTC time in ISO 8601 format.
- `get_current_local_date_time`: Returns current local system time in ISO 8601 format.

### 6. UtilServer (C++ - Port 4006)

- `tokenize_string`: Splits a string by specified delimiters.
- `count_words`: Counts exact words in a string.
- `extract_json_from_text`: Extracts the first valid JSON object/array from messy text.
- `generate_uuid`: Generates a random UUID v4 string.

### 7. PDFServer (Python - Port 4007)

- `create_pdf`: Creates a new PDF with provided text at an absolute path.
- `read_pdf`: Extracts all text content from a PDF.
- `append_text_to_pdf`: Appends a new page with text to an existing PDF.

---

## 6. Framework Configuration

### `registry.txt`

Located in the aJent root directory. Contains a list of URLs for active tool servers, one per line.

```text
http://localhost:4001
http://localhost:4002
http://localhost:4003
http://localhost:4004
http://localhost:4005
http://localhost:4006
http://localhost:4007
```

### Workspace Injection (`aJentWorkbench`)

The entry point dynamically detects the user's Home directory and appends `/aJentWorkbench`. It ensures this folder exists and injects the absolute path into the Orchestrator's system prompt, instructing the LLM to use absolute paths for all file operations.

---

## 7. Execution & Usage

### Step 1: Start the Tool Server Fleet

Ensure your C++ binaries are compiled and your Python virtual environment is set up. Start each tool server in a separate terminal window (or in the background). For example:

```bash
./http_tool_servers/dirserver/bin/dirserver
./http_tool_servers/fileserver/bin/fileserver
# ... (start the rest)
```

### Step 2: Start the LLM Provider

aJent requires an OpenAI-compatible API. For local execution, `llama.cpp` is highly recommended.

**Recommended Context Setup:**
To prevent context overflow errors during complex tasks, allocate a large context window (`-c 16384`) and restrict the server to a single parallel slot (`-np 1`) so the agent gets the full context capacity.

**Recommended Models:** `Qwen2.5-32B-Instruct` or `Qwen2.5-14B-Instruct` (Excellent tool-calling adherence).

```bash
llama-server --jinja -m ~/llms/Qwen2.5-32B-Instruct-Q4_K_M.gguf \
  --n-gpu-layers 999 --parallel 1 --cont-batching \
  --ctx-size 16384 --batch-size 1024 --flash-attn on \
  --host 127.0.0.1 --port 8080
```

### Step 3: Build and Run aJent

Build the uberjar:

```bash
lein uberjar
```

Run the framework:

```bash
java -jar target/ajent.jar http://localhost:8080 <model-name> <temperature>
# Example:
java -jar target/ajent.jar http://localhost:8080 Qwen2.5-32B-Instruct 0.7
```

You will be greeted with `aJent>`. Type your prompts and press Enter. Type `exit` or `quit` to stop.

---

## 8. Advanced Orchestrator Capabilities

The Orchestrator's system prompt enforces advanced Agentic behaviors:

1. **Concurrent Execution:** If the user requests multiple independent tasks, the LLM emits multiple tool calls in one response. The framework executes them concurrently in parallel.
2. **Error Recovery:** If a tool returns an error, the LLM is instructed NOT to report failure immediately, but to analyze the error, adjust its arguments, and try again.
3. **Large Content Chunking:** The system prompt explicitly directs the LLM to avoid generating massive text strings in a single tool call, instead using file appending tools to build large documents incrementally.
4. **Contextual Resolution:** The Orchestrator holds the full conversation history. If the user says "read that file", the LLM resolves "that file" from the context window before calling a tool.

---

## 9. Release Notes

**aJent v1.0.0**
Initial public release of the aJent Unified Concurrent Orchestrator.

**Features:**
* Non-streaming ReAct loop for stable tool-call parsing.
* Concurrent parallel tool execution via `pmap`.
* Sliding context window and output truncation to prevent LLM crashes.
* Polyglot HTTP tool server support (C++, Python, Clojure).

**Prerequisites for running the JAR:**
* **Java 21+** installed (Clojure 1.12 requires a modern Java).
* The **HTTP Tool Servers** running (they can't just run the JAR in a vacuum).
* A local **LLM server** (like `llama-server`) running.

After everything is set up successfully, run the sample command below in your terminal to start chatting:

```bash
java -jar ajent.jar http://localhost:8080 gpt-oss-20b 0.7
```
*(Note: Replace `gpt-oss-20b` with whatever model you have loaded in your local LLM server, such as `Qwen2.5-32B-Instruct`)*

---

## Acknowledgements

I would like to express my gratitude to my AI assistant and mentor, **GLM-5.2**, for their exceptional consultancy and collaborative support throughout the development of this project. Your guidance was invaluable in bringing this framework to life.

## License

Copyright © 2026 Pooria Yousefi

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0/.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

***
