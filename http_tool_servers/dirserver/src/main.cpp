#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <filesystem>
#include <string>
#include <vector>
#include <chrono>
#include <system_error>

using json = nlohmann::json;
namespace fs = std::filesystem;

namespace pooriayousefi
{
    /// Maximum number of entries returned by the recursive listing tool, to bound
    /// memory/response size on very large trees instead of buffering unboundedly.
    constexpr std::size_t MAX_RECURSIVE_ENTRIES = 50'000;

    // ---------------------------------------------------------------------
    // Small JSON helpers (avoid repeating the {"error", ...} shape everywhere)
    // ---------------------------------------------------------------------
    inline json make_error(const std::string &message)
    {
        return {{"error", message}, {"is_error", true}};
    }

    inline json make_result(json value)
    {
        return {{"result", std::move(value)}, {"is_error", false}};
    }

    /// Extracts a required string argument, returning an error json on failure.
    /// Never throws even if the JSON value is present but not a string.
    bool get_required_string(const json &args, const char *key, std::string &out, json &error_out)
    {
        if (!args.contains(key) || !args[key].is_string())
        {
            error_out = make_error(std::string("Missing or non-string '") + key + "' in arguments");
            return false;
        }
        out = args[key].get<std::string>();
        return true;
    }

    // ---------------------------------------------------------------------
    // Tool implementations
    // ---------------------------------------------------------------------

    json tool_create_directory(const std::string &path)
    {
        std::error_code ec;
        fs::create_directories(path, ec);
        if (ec)
            return make_error("Failed to create directory: " + ec.message());

        return make_result("Directory created successfully (or already existed) at " + path);
    }

    json tool_list_directory(const std::string &path)
    {
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
            return make_error("Directory not found: " + path);

        json content = json::array();
        fs::directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
        if (ec)
            return make_error("Failed to open directory: " + ec.message());

        for (const auto &entry : it)
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
        return make_result(content);
    }

    json tool_iterate_directories_recursively(const std::string &path)
    {
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
            return make_error("Directory not found: " + path);

        json content = json::array();
        bool truncated = false;

        fs::recursive_directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
        fs::recursive_directory_iterator end;
        if (ec)
            return make_error("Failed to open directory: " + ec.message());

        for (; it != end; it.increment(ec))
        {
            if (ec)
                break;

            if (content.size() >= MAX_RECURSIVE_ENTRIES)
            {
                truncated = true;
                break;
            }
            content.push_back(it->path().string()); // Return full path for clarity
        }

        json result;
        result["entries"] = std::move(content);
        result["truncated"] = truncated;
        return make_result(result);
    }

    json tool_delete_directory(const std::string &path)
    {
        std::error_code ec;
        if (!fs::exists(path, ec))
            return make_error("Directory not found: " + path);

        auto removed_count = fs::remove_all(path, ec);
        if (ec)
            return make_error("Failed to delete directory: " + ec.message());

        return make_result("Successfully deleted " + std::to_string(removed_count) + " items at " + path);
    }

    json tool_get_last_modified_file(const std::string &path)
    {
        std::error_code ec;
        if (!fs::is_directory(path, ec) || ec)
            return make_error("Directory not found: " + path);

        fs::path latest_file;
        fs::file_time_type latest_time{};
        bool found = false;

        fs::recursive_directory_iterator it(path, fs::directory_options::skip_permission_denied, ec);
        fs::recursive_directory_iterator end;
        if (ec)
            return make_error("Failed to open directory: " + ec.message());

        for (; it != end; it.increment(ec))
        {
            if (ec)
                break;

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
            return make_error("No files found in directory: " + path);

        return make_result(latest_file.string());
    }

    /**
     * @brief Returns the JSON schema defining the tools provided by this server.
     */
    json get_schema()
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
                                "path": { "type": "string", "description": "The absolute path of the directory to create (e.g., '/Users/user/AjentWorkbench/new_folder')." }
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
                        "description": "Iterates a given directory recursively and returns a flat list of all files and folders inside it (capped; response includes a 'truncated' flag).",
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
        return json::parse(schema_str, nullptr, false);
    }

    /**
     * @brief Handles the execution of a requested tool.
     */
    json handle_tool_call(const json &args, const std::string &tool_name)
    {
        std::string path;
        json arg_error;

        if (tool_name == "create_directory")
        {
            if (!get_required_string(args, "path", path, arg_error)) return arg_error;
            return tool_create_directory(path);
        }
        else if (tool_name == "list_directory")
        {
            if (!get_required_string(args, "path", path, arg_error)) return arg_error;
            return tool_list_directory(path);
        }
        else if (tool_name == "iterate_directories_recursively")
        {
            if (!get_required_string(args, "path", path, arg_error)) return arg_error;
            return tool_iterate_directories_recursively(path);
        }
        else if (tool_name == "delete_directory")
        {
            if (!get_required_string(args, "path", path, arg_error)) return arg_error;
            return tool_delete_directory(path);
        }
        else if (tool_name == "get_last_modified_file")
        {
            if (!get_required_string(args, "path", path, arg_error)) return arg_error;
            return tool_get_last_modified_file(path);
        }
        else
        {
            return make_error("Unknown tool: " + tool_name);
        }
    }
}

/**
 * @brief Main entry point for the Directory Server.
 */
int main()
{
    httplib::Server svr;

    svr.set_exception_handler(
        [](const httplib::Request &, httplib::Response &res, const std::exception_ptr &ep)
        {
            std::string message = "Internal server error";
            try
            {
                if (ep) std::rethrow_exception(ep);
            }
            catch (const std::exception &e)
            {
                message = e.what();
            }
            res.status = 500;
            res.set_content(json({{"error", message}, {"is_error", true}}).dump(), "application/json");
        });

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
            json req_body = json::parse(req.body, nullptr, false);
            if (req_body.is_discarded())
            {
                res.status = 400;
                res.set_content(json({{"error", "Invalid JSON payload"}}).dump(), "application/json");
                return;
            }

            std::string tool_name = req_body.value("name", "");
            json arguments = req_body.value("arguments", json::object());

            std::cout << "[DirServer] Received call for tool: " << tool_name << std::endl;

            json result = pooriayousefi::handle_tool_call(arguments, tool_name);
            res.set_content(result.dump(), "application/json");
        }
    );

    std::cout << "DirServer running on http://localhost:4001" << std::endl;

    svr.listen("127.0.0.1", 4001);

    return 0;
}