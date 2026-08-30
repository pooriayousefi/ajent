#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <string>
#include <array>
#include <memory>
#include <cstdlib>
#include <algorithm>
#include <cctype>

using Json = nlohmann::json;

// Cross-platform popen/pclose definitions
#ifdef _WIN32
    #define POPEN _popen
    #define PCLOSE _pclose
    #define NULL_DEVICE "NUL"
#else
    #define POPEN popen
    #define PCLOSE pclose
    #define NULL_DEVICE "/dev/null"
#endif

namespace pooriayousefi
{
    /**
     * @brief Escapes a string to be safely used as a single-quoted argument in a shell command.
     */
    std::string escape_shell_arg(const std::string& arg) 
    {
        std::string out = "'";
        for (char c : arg) 
        {
            if (c == '\'') 
            {
                out += "'\\''";
            } 
            else 
            {
                out += c;
            }
        }
        out += "'";
        return out;
    }

    /**
     * @brief Returns true if this server has been explicitly enabled to execute commands.
     *
     * SECURITY: This server executes arbitrary shell commands with the privileges
     * of the process running it. It is disabled by default. An operator must
     * explicitly opt in by setting AJENT_SHELL_ENABLED=1 in the environment
     * before starting the process, acknowledging the risk.
     */
    bool shell_execution_enabled()
    {
        const char* flag = std::getenv("AJENT_SHELL_ENABLED");
        return flag != nullptr && std::string(flag) == "1";
    }

    /**
     * @brief Lowercases a string for case-insensitive pattern checks.
     */
    std::string to_lower(std::string s)
    {
        std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
        return s;
    }

    /**
     * @brief Best-effort denylist for a handful of unambiguously destructive patterns.
     *
     * NOTE: This is a floor, not a sandbox. It catches obvious footguns but is
     * trivially bypassable (encoding tricks, indirection, etc.) by a determined
     * caller. Real containment requires running this server in a restricted
     * environment (container/VM with no access to secrets or the host
     * filesystem, seccomp/AppArmor profile, non-root user, resource limits,
     * network egress restrictions, and ideally a human-in-the-loop confirmation
     * step in the calling agent before high-risk commands are dispatched here).
     */
    bool is_denylisted(const std::string& command)
    {
        static const std::array<std::string, 8> denylist = {
            "rm -rf /",
            "rm -rf --no-preserve-root",
            "mkfs",
            "dd if=/dev/zero",
            "dd of=/dev/sd",
            ":(){ :|:& };:", // fork bomb
            "shutdown",
            "reboot"
        };

        const std::string lowered = to_lower(command);
        for (const auto& pattern : denylist)
        {
            if (lowered.find(pattern) != std::string::npos)
                return true;
        }
        return false;
    }

    /**
     * @brief Returns the JSON schema defining the tools provided by this server.
     */
    Json get_schema() 
    {
        std::string schema_str = R"(
            [
                {
                    "type": "function",
                    "function": {
                        "name": "run_command",
                        "description": "Executes an arbitrary shell command locally and returns combined standard output and standard error. This is a high-risk development tool; the framework must enforce any confirmation or execution policy outside the LLM prompt.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "command": { "type": "string", "description": "The exact shell command to execute (e.g., 'ls -la' or 'python main.py')." },
                                "input": { "type": "string", "description": "Optional. Standard input to feed to the command (e.g., a password for sudo). If provided, it is piped into the command." }
                            },
                            "required": ["command"]
                        }
                    }
                }
            ]
        )";
        return Json::parse(schema_str, nullptr, false);
    }

    /**
     * @brief Executes a shell command and captures its output.
     */
    std::string exec_command(const std::string& cmd, const std::string& input_str) 
    {
        std::array<char, 128> buffer;
        std::string result;
        
        std::string safe_cmd;
        if (!input_str.empty()) 
        {
            safe_cmd = "printf '%s' " + escape_shell_arg(input_str) + " | " + cmd + " 2>&1";
        } 
        else 
        {
            safe_cmd = cmd + " 2>&1";
        }
        
        std::unique_ptr<FILE, decltype(&PCLOSE)> pipe(POPEN(safe_cmd.c_str(), "r"), PCLOSE);
        if (!pipe) 
        {
            return "Error: Failed to open pipe for command execution.";
        }
        
        while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) 
        {
            result += buffer.data();
        }
        
        return result;
    }

    /**
     * @brief Handles the execution of a requested tool.
     */
    Json handle_tool_call(const Json& args, const std::string& tool_name) 
    {
        if (tool_name == "run_command") 
        {
            if (!shell_execution_enabled())
            {
                return {{"error", "Shell execution is disabled on this server. Set AJENT_SHELL_ENABLED=1 to enable it, and only do so in a sandboxed/restricted environment."}, {"is_error", true}};
            }

            if (!args.contains("command")) 
            {
                return {{"error", "Missing 'command' in arguments"}, {"is_error", true}};
            }
            
            std::string command = args["command"].get<std::string>();
            std::string input_str = args.value("input", "");

            if (is_denylisted(command))
            {
                return {{"error", "Command rejected: matches a denylisted destructive pattern."}, {"is_error", true}};
            }
            
            std::cout << "[ShellServer] Executing: " << command << std::endl;
            
            std::string output = exec_command(command, input_str);
            
            if (output.length() > 8000) 
            {
                output = output.substr(0, 8000) + "\n...[Output truncated]...";
            }
            
            return {{"result", output}, {"is_error", false}};
        }
        return {{"error", "Unknown tool: " + tool_name}, {"is_error", true}};
    }
}

/**
 * @brief Main entry point for the Shell Execute tool server.
 */
int main() 
{
    httplib::Server svr;

    svr.Get(
        "/schema", 
        [](const httplib::Request&, httplib::Response& res) 
        {
            res.set_content(pooriayousefi::get_schema().dump(), "application/json");
        }
    );

    svr.Post(
        "/tools/call", 
        [](const httplib::Request& req, httplib::Response& res) 
        {
            Json req_body = Json::parse(req.body, nullptr, false);
            if (req_body.is_discarded()) 
            {
                res.status = 400;
                res.set_content(Json({{"error", "Invalid JSON"}}).dump(), "application/json");
                return;
            }

            std::string tool_name = req_body.value("name", "");
            Json arguments = req_body.value("arguments", Json::object());

            Json result = pooriayousefi::handle_tool_call(arguments, tool_name);
            res.set_content(result.dump(), "application/json");
        }
    );

    std::cout << "Interactive Shell Execute Server running on http://localhost:4004" << std::endl;
    std::cout << "WARNING: This server executes arbitrary shell commands." << std::endl;
    if (!pooriayousefi::shell_execution_enabled())
    {
        std::cout << "Shell execution is currently DISABLED. Set AJENT_SHELL_ENABLED=1 to enable it." << std::endl;
    }
    svr.listen("127.0.0.1", 4004);
    return 0;
}