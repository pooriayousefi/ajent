
# ⏱️ Time Server (aJent Tool)

A lightweight, high-performance RESTful microservice built in Clojure that provides time-related tools to the **aJent** Orchestrator. 

It adheres to the aJent OpenAI-compatible REST Tool Server contract, exposing a `/schema` endpoint for dynamic discovery and a `/tools/call` endpoint for execution.

## 🛠️ Available Tools

When connected to aJent, this server provides the following capabilities to the LLM:

1. **`get_current_time`**: Returns the current UTC date and time in ISO-8601 format.
2. **`get_local_time`**: Returns the current local date and time in ISO-8601 format based on the host machine's default timezone.
3. **`get_unix_timestamp`**: Returns the current Unix epoch timestamp in seconds.

## 🚀 How to Run

You can run this server either by using the pre-built JAR or directly from the source code using Leiningen. It accepts optional `[host]` and `[port]` arguments.

*Defaults to `127.0.0.1` and port `4006` if no arguments are provided.*

### Option A: Using the Pre-built JAR
If you downloaded the `time-server.jar` from the [aJent GitHub Releases](https://github.com/pooriayousefi/ajent/releases):

```bash
java -jar time-server.jar [host] [port]
```

### Option B: Building from Source
If you have [Leiningen](https://leiningen.org/) installed, you can run or build the server from the source code in this directory.

**Run directly:**
```bash
lein run 127.0.0.1 4006
```

**Build an uberjar:**
```bash
lein uberjar
java -jar target/time-server.jar 127.0.0.1 4006
```

## 🔌 Integration with aJent

To allow the aJent orchestrator to discover and use this server, add the following entry to your aJent project's `tool_servers.json` file:

```json
{
  "rest_servers": [
    {
      "name": "time-server",
      "url": "http://127.0.0.1:4006",
      "enabled": true
    }
  ]
}
```

## 📡 API Contract Reference

If you are building custom clients or debugging, the server exposes the following endpoints:

### `GET /schema`
Returns a JSON array of OpenAI-compatible tool schemas.
```json
[
  {
    "type": "function",
    "function": {
      "name": "get_current_time",
      "description": "Returns the current UTC date and time in ISO-8601 format.",
      "parameters": {
        "type": "object",
        "properties": {}
      }
    }
  }
]
```

### `POST /tools/call`
Executes a tool call. 
**Request Body:**
```json
{
  "name": "get_unix_timestamp",
  "arguments": {}
}
```
**Response Body:**
```json
{
  "result": 1718000000,
  "is_error": false
}
```

## 📜 License
Distributed under the Eclipse Public License, the same as Clojure.
```