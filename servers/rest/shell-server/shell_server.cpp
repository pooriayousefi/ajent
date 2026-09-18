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
#else
    #define POPEN popen
    #define PCLOSE pclose
#endif

namespace pooriayousefi
{
    std::string escape_shell_arg(const std::string& arg) 
    {
        std::string out = "'";
        for (char c : arg) 
        {
            if (c == '\'') out += "'\\''";
            else out += c;
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
        static const std::array<std::string, 10> denylist = {
            "rm -rf /", "rm -rf --no-preserve-root", "mkfs", "dd if=/dev/zero", "dd of=/dev/sd",
            ":(){ :|:& };:", "shutdown", "reboot", "halt", "init 0"
        };

        const std::string lowered = to_lower(command);
        for (const auto& pattern : denylist) 
        {
            if (lowered.find(pattern) != std::string::npos) return true;
        }
        return false;
    }

    JSON get_schema() 
    {
        return JSON::parse(R"(
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
        )");
    }

    std::string exec_command(const std::string& cmd, const std::string& input_str) 
    {
        std::array<char, 128> buffer{};
        std::string result{};
        
        std::string safe_cmd = !input_str.empty() 
            ? "printf '%s' " + escape_shell_arg(input_str) + " | " + cmd + " 2>&1"
            : cmd + " 2>&1";
        
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

    JSON handle_tool_call(const JSON& args, const std::string& tool_name) 
    {
        try 
        {
            if (tool_name == "run_command") 
            {
                if (!shell_execution_enabled()) 
                {
                    return { {"error", "Shell execution is disabled on this server. Set AJENT_SHELL_ENABLED=1 to enable it, and only do so in a sandboxed/restricted environment."}, {"is_error", true} };
                }
                
                if (!args.contains("command")) 
                {
                    return { {"error", "Missing 'command' in arguments"}, {"is_error", true} };
                }
                
                std::string command = args["command"].get<std::string>();
                std::string input_str = args.value("input", "");

                if (is_denylisted(command)) 
                {
                    return { {"error", "Command rejected: matches a denylisted catastrophic pattern."}, {"is_error", true} };
                }
                
                std::cout << "[shell REST server] Executing: " << command << std::endl;
                std::string output = exec_command(command, input_str);

                if (output.length() > 8000) 
                {
                    output = output.substr(0, 8000) + "\n...[Output truncated]...";
                }

                return { {"result", output}, {"is_error", false} };
            }
            return { {"error", "Unknown tool: " + tool_name}, {"is_error", true} };
        } 
        catch (const std::exception& e) 
        {
            return { {"error", "Exception in using tool " + tool_name + ": " + std::string(e.what())}, {"is_error", true}};
        }
    }
}

int main(int argc, char* argv[]) 
{
    std::string host = "127.0.0.1";
    int port = 0;
    try 
    {
        if (argc != 3) throw std::invalid_argument("Usage: shell_server <host> <port>");
        host = argv[1];
        port = std::stoi(argv[2]);

        httplib::Server svr;
        svr.Get("/schema", [](const httplib::Request&, httplib::Response& res) {
            res.set_content(pooriayousefi::get_schema().dump(), "application/json");
        });
        svr.Post("/tools/call", [](const httplib::Request& req, httplib::Response& res) {
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
        });

        std::cout << "shell REST server running on http://" << host << ":" << port << std::endl;
        if (!pooriayousefi::shell_execution_enabled()) 
        {
            std::cout << "WARNING: Shell execution is currently DISABLED. Set AJENT_SHELL_ENABLED=1 to enable it." << std::endl;
        }
        
        svr.listen(host, port);
    } 
    catch(const std::exception& e) 
    {
        std::cerr << e.what() << '\n';
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}