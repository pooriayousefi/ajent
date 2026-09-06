#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <string>
#include <array>
#include <memory>
#include <cstdlib>
#include <algorithm>
#include <cctype>

using JSON = nlohmann::json;

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

    bool shell_execution_enabled()
    {
        const char* flag = std::getenv("AJENT_SHELL_ENABLED");
        return flag != nullptr && std::string(flag) == "1";
    }

    std::string to_lower(std::string s)
    {
        std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return std::tolower(c); });
        return s;
    }

    bool is_denylisted(const std::string& command)
    {
        // Absolute hard denylist for catastrophic system commands
        static const std::array<std::string, 10> denylist = {
            "rm -rf /",
            "rm -rf --no-preserve-root",
            "mkfs",
            "dd if=/dev/zero",
            "dd of=/dev/sd",
            ":(){ :|:& };:",
            "shutdown",
            "reboot",
            "halt",
            "init 0"
        };

        const std::string lowered = to_lower(command);
        for (const auto& pattern : denylist)
        {
            if (lowered.find(pattern) != std::string::npos)
            {
                return true;
            }
        }
        return false;
    }

    JSON get_schema() 
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
        return JSON::parse(schema_str, nullptr, false);
    }

    std::string exec_command(const std::string& cmd, const std::string& input_str) 
    {
        std::array<char, 128> buffer{};
        std::string result{};
        
        std::string safe_cmd{};
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
            result.assign("Error: Failed to open pipe for command execution.");
        }
        else
        {
            while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr)
            {
                result += buffer.data();
            }
        }
        return result;
    }

    JSON handle_tool_call(const JSON& args, const std::string& tool_name) 
    {
        JSON result;
        try
        {
            if (tool_name == "run_command")
            {
                if (!shell_execution_enabled())
                {
                    result = { {"error", "Shell execution is disabled on this server. Set AJENT_SHELL_ENABLED=1 to enable it, and only do so in a sandboxed/restricted environment."}, {"is_error", true} };
                }
                else
                {
                    if (!args.contains("command"))
                    {
                        result = { {"error", "Missing 'command' in arguments"}, {"is_error", true} };
                    }
                    else
                    {
                        std::string command = args["command"].get<std::string>();
                        std::string input_str = args.value("input", "");

                        if (is_denylisted(command))
                        {
                            result = { {"error", "Command rejected: matches a denylisted catastrophic pattern."}, {"is_error", true} };
                        }
                        else
                        {
                            std::cout << "[shell REST server] Executing: " << command << std::endl;

                            std::string output = exec_command(command, input_str);

                            if (output.length() > 8000)
                            {
                                output = output.substr(0, 8000) + "\n...[Output truncated]...";
                            }

                            result = { {"result", output}, {"is_error", false} };
                        }
                    }
                }
            }
            else
            {
                result = { {"error", "Unknown tool: " + tool_name}, {"is_error", true} };
            }
        }
        catch (const std::exception& e)
        {
            result = { {"error", "Exception in using tool " + tool_name + ": " + std::string(e.what())}, {"is_error", true}};
        }
        return result;
    }
}

int main(int argc, char* argv[]) 
{
    std::string host = "127.0.0.1";
    int port = 0;
    auto exit_code{ 0 };

    try
    {
        if (argc != 3)
        {
            throw std::invalid_argument("Usage: shell_server <host> <port>");
        }

        host = argv[1];
        port = std::stoi(argv[2]);

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
                JSON req_body = JSON::parse(req.body, nullptr, false);
                if (req_body.is_discarded()) 
                {
                    res.status = 400;
                    res.set_content(JSON({{"error", "Invalid json"}}).dump(), "application/json");
                    return;
                }

                std::string tool_name = req_body.value("name", "");
                JSON arguments = req_body.value("arguments", JSON::object());

                JSON result = pooriayousefi::handle_tool_call(arguments, tool_name);
                res.set_content(result.dump(), "application/json");
            }
        );

        std::cout << "shell REST server running on http://" << host << ":" << port << std::endl;
        std::cout << "WARNING: This server executes arbitrary shell commands." << std::endl;
        if (!pooriayousefi::shell_execution_enabled())
        {
            std::cout << "Shell execution is currently DISABLED. Set AJENT_SHELL_ENABLED=1 to enable it." << std::endl;
        }
        
        svr.listen(host, port);
        exit_code = EXIT_SUCCESS;
    }
    catch(const std::exception& e)
    {
        std::cerr << e.what() << '\n';
        exit_code = EXIT_FAILURE;
    }
    return exit_code;
}