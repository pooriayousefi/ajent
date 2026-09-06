#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <filesystem>
#include <string>
#include <vector>
#include <chrono>
#include <system_error>

// Type aliases for convenience
using JSON = nlohmann::json;
namespace fs = std::filesystem;

namespace pooriayousefi
{
    constexpr std::size_t MAX_RECURSIVE_ENTRIES = 50'000;

    // Helper functions
    inline JSON make_error(const std::string &message)
    {
        return {{"error", message}, {"is_error", true}};
    }

    inline JSON make_result(JSON value)
    {
        return {{"result", std::move(value)}, {"is_error", false}};
    }

    bool get_required_string(const JSON &args, const char *key, std::string &out, JSON &error_out)
    {
        auto result{ true };
        if (!args.contains(key) || !args[key].is_string())
        {
            error_out = make_error(std::string("Missing or non-string '") + key + "' in arguments");
            result = false;
        }
        out = args[key].get<std::string>();
        return result;
    }

    JSON tool_create_directory(const std::string &path)
    {
        JSON result;
        std::error_code ec;
        fs::create_directories(path, ec);
        if (ec)
        {
            result = make_error("Failed to create directory: " + ec.message());
        }
        result = make_result("Directory created successfully (or already existed) at " + path);
        return result;
    }

    JSON tool_list_directory(const std::string &path)
    {
        JSON result;
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
        {
            result = make_error("Directory not found: " + path);
        }
        else
        {
            JSON content = JSON::array();
            fs::directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
            if (ec)
            {
                result = make_error("Failed to open directory: " + ec.message());
            }
            else
            {
                for (const auto& entry : it)
                {
                    std::error_code entry_ec;
                    bool is_dir = entry.is_directory(entry_ec);
                    std::uintmax_t size = 0;
                    if (!is_dir)
                    {
                        size = entry.file_size(entry_ec);
                    }
                    content.push_back({
                        {"name", entry.path().filename().string()},
                        {"is_directory", is_dir},
                        {"size", size}
                        });
                }
                result = make_result(content);
            }
        }
        return result;        
    }

    JSON tool_iterate_directories_recursively(const std::string &path)
    {
        JSON result;
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
        {
            result = make_error("Directory not found: " + path);
        }
        else
        {
            JSON content = JSON::array();
            bool truncated = false;

            fs::recursive_directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
            fs::recursive_directory_iterator end;
            if (ec)
            {
                result = make_error("Failed to open directory: " + ec.message());
            }
            else
            {
                for (; it != end; it.increment(ec))
                {
                    if (ec)
                    {
                        break;
                    }

                    if (content.size() >= MAX_RECURSIVE_ENTRIES)
                    {
                        truncated = true;
                        break;
                    }
                    content.push_back(it->path().string());
                }
                result["entries"] = std::move(content);
                result["truncated"] = truncated;
                result = make_result(result);
            }
        }
        return result;
    }

    JSON tool_delete_directory(const std::string &path)
    {
        JSON result;
        std::error_code ec;
        if (!fs::exists(path, ec))
        {
            result = make_error("Directory not found: " + path);
        }
        else
        {
            auto removed_count = fs::remove_all(path, ec);
            if (ec)
            {
                result = make_error("Failed to delete directory: " + ec.message());
            }
            else
            {
                result = make_result("Successfully deleted " + std::to_string(removed_count) + " items at " + path);
            }
        }
        return result;
    }

    JSON tool_get_last_modified_file(const std::string &path)
    {
        JSON result;
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
        {
            result = make_error("Directory not found: " + path);
        }
        else
        {
            fs::path latest_file;
            fs::file_time_type latest_time{};
            bool found = false;

            fs::recursive_directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
            fs::recursive_directory_iterator end;
            if (ec)
            {
                result = make_error("Failed to open directory: " + ec.message());
            }
            else
            {
                for (; it != end; it.increment(ec))
                {
                    if (ec)
                    {
                        break;
                    }

                    std::error_code file_ec;
                    if (it->is_regular_file(file_ec) && !file_ec)
                    {
                        std::error_code time_ec;
                        auto ftime = it->last_write_time(time_ec);
                        if (!time_ec && (!found || ftime > latest_time))
                        {
                            latest_time = ftime;
                            latest_file = it->path();
                            found = true;
                        }
                    }
                }
                if (!found)
                {
                    result = make_error("No files found in directory: " + path);
                }
                else
                {
                    result = make_result(latest_file.string());
                }
            }
        }
        return result;
    }

    JSON get_schema()
    {
        std::string schema_str = R"(
            [
                {
                    "type": "function",
                    "function": {
                        "name": "create_directory",
                        "description": "Creates a directory at the given absolute path. If it already exists, does nothing.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "path": { "type": "string", "description": "The absolute path of the directory to create." }
                            },
                            "required": ["path"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "list_directory",
                        "description": "Lists the immediate files and folders inside a given directory (non-recursive).",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "path": { "type": "string", "description": "The absolute path of the directory to list." }
                            },
                            "required": ["path"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "iterate_directories_recursively",
                        "description": "Iterates a given directory recursively and returns a flat list of all files and folders inside it.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "path": { "type": "string", "description": "The absolute path of the directory which has to be iterated." }
                            },
                            "required": ["path"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "delete_directory",
                        "description": "Deletes a directory and all of its contents recursively. Use with caution.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "path": { "type": "string", "description": "The absolute path of the directory to delete." }
                            },
                            "required": ["path"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "get_last_modified_file",
                        "description": "Finds and returns the absolute path of the most recently modified file in a given directory.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "path": { "type": "string", "description": "The absolute path of the directory to search." }
                            },
                            "required": ["path"]
                        }
                    }
                }
            ]
        )";
        return JSON::parse(schema_str, nullptr, false);
    }

    JSON handle_tool_call(const JSON &args, const std::string &tool_name)
    {
        std::string path;
        JSON arg_error, result;

        if (tool_name == "create_directory")
        {
            if (!get_required_string(args, "path", path, arg_error))
            {
                result = arg_error;
            }
            else
            {
                result = tool_create_directory(path);
            }
        }
        else if (tool_name == "list_directory")
        {
            if (!get_required_string(args, "path", path, arg_error))
            {
                result = arg_error;
            }
            else
            {
                result = tool_list_directory(path);
            }
        }
        else if (tool_name == "iterate_directories_recursively")
        {
            if (!get_required_string(args, "path", path, arg_error))
            {
                result = arg_error;
            }
            else
            {
                result = tool_iterate_directories_recursively(path);
            }
        }
        else if (tool_name == "delete_directory")
        {
            if (!get_required_string(args, "path", path, arg_error))
            {
                result = arg_error;
            }
            else
            {
                result = tool_delete_directory(path);
            }
        }
        else if (tool_name == "get_last_modified_file")
        {
            if (!get_required_string(args, "path", path, arg_error))
            {
                result = arg_error;
            }
            else
            {
                result = tool_get_last_modified_file(path);
            }
        }
        else
        {
            result = make_error("Unknown tool: " + tool_name);
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
            throw std::invalid_argument("Usage: directory_server <host> <port>");
        }

        host = argv[1];
        port = std::stoi(argv[2]);

        httplib::Server svr;

        svr.set_exception_handler(
            [](const httplib::Request &, httplib::Response &res, const std::exception_ptr &ep)
            {
                std::string message = "Internal server error";
                try
                {
                    if (ep)
                    {
                        std::rethrow_exception(ep);
                    }
                }
                catch (const std::exception &e)
                {
                    message = e.what();
                }
                res.status = 500;
                res.set_content(JSON({{"error", message}, {"is_error", true}}).dump(), "application/json");
            }
        );

        svr.Get(
            "/schema",
            [](const httplib::Request &, httplib::Response &res)
            {
                res.set_content(pooriayousefi::get_schema().dump(), "application/json");
            }
        );

        svr.Post(
            "/tools/call",
            [](const httplib::Request &req, httplib::Response &res)
            {
                JSON req_body = JSON::parse(req.body, nullptr, false);
                if (req_body.is_discarded())
                {
                    res.status = 400;
                    res.set_content(JSON({{"error", "Invalid json payload"}}).dump(), "application/json");
                    return;
                }

                std::string tool_name = req_body.value("name", "");
                JSON arguments = req_body.value("arguments", JSON::object());

                std::cout << "[directory REST server] Received call for tool: " << tool_name << std::endl;

                JSON result = pooriayousefi::handle_tool_call(arguments, tool_name);
                res.set_content(result.dump(), "application/json");
            }
        );

        std::cout << "directory REST server running on http://" << host << ":" << port << std::endl;
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