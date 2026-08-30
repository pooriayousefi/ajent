#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <system_error>

using json = nlohmann::json;
namespace fs = std::filesystem;

namespace pooriayousefi
{
    json get_schema()
    {
        return json::parse(R"([
            {"type":"function","function":{"name":"write_text_file","description":"Writes text content to a file at the specified absolute path. Creates parent directories if needed. Overwrites if file exists.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path (e.g., '/Users/user/AjentWorkbench/file.txt')."},"content":{"type":"string","description":"The text content to write."}},"required":["path","content"]}}},
            {"type":"function","function":{"name":"read_text_file","description":"Reads text content from a regular file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to read."}},"required":["path"]}}},
            {"type":"function","function":{"name":"append_text_file","description":"Appends text content to a file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to append to."},"content":{"type":"string","description":"The text content to add."}},"required":["path","content"]}}},
            {"type":"function","function":{"name":"delete_file","description":"Deletes a regular file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to delete."}},"required":["path"]}}},
            {"type":"function","function":{"name":"file_exists","description":"Checks whether a path exists at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to check."}},"required":["path"]}}},
            {"type":"function","function":{"name":"get_file_info","description":"Returns metadata for a path at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to inspect."}},"required":["path"]}}}
        ])");
    }

    json error(std::string message) { return {{"error", std::move(message)}, {"is_error", true}}; }
    json success(json value) { return {{"result", std::move(value)}, {"is_error", false}}; }

    json handle_tool_call(const json& args, const std::string& tool_name)
    {
        try
        {
            if (!args.is_object()) return error("Arguments must be a JSON object.");
            if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
            const std::string path_str = args["path"].get<std::string>();
            fs::path path(path_str);

            if (tool_name == "write_text_file" || tool_name == "append_text_file")
            {
                if (!args.contains("content") || !args["content"].is_string()) return error("Missing or invalid 'content'.");
                
                // Create parent directories if they don't exist
                fs::path parent = path.parent_path();
                if (!parent.empty()) {
                    std::error_code ec;
                    fs::create_directories(parent, ec);
                    if (ec) return error("Failed to create parent directories: " + ec.message());
                }

                std::ofstream out(path, tool_name == "append_text_file" ? (std::ios::app | std::ios::binary) : (std::ios::out | std::ios::binary | std::ios::trunc));
                if (!out) return error("Failed to open file for writing.");
                out << args["content"].get<std::string>();
                return success(tool_name == "append_text_file" ? "Content appended successfully." : "File written successfully.");
            }

            if (tool_name == "read_text_file")
            {
                if (!fs::is_regular_file(path)) return error("Path is not a regular file.");
                std::ifstream in(path, std::ios::binary);
                if (!in) return error("Failed to open file for reading.");
                return success(std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>()));
            }
            if (tool_name == "delete_file")
            {
                if (!fs::is_regular_file(path)) return error("Path is not a regular file.");
                std::error_code ec;
                if (!fs::remove(path, ec) || ec) return error("Failed to delete file: " + ec.message());
                return success("File deleted successfully.");
            }
            if (tool_name == "file_exists")
            {
                return success(fs::exists(path));
            }
            if (tool_name == "get_file_info")
            {
                std::error_code ec;
                bool exists = fs::exists(path, ec);
                json info = {
                    {"path", path_str},
                    {"exists", exists},
                    {"is_directory", exists && fs::is_directory(path)},
                    {"is_regular_file", exists && fs::is_regular_file(path)}
                };
                if (exists && fs::is_regular_file(path)) info["size_bytes"] = fs::file_size(path);
                return success(info);
            }
            return error("Unknown tool: " + tool_name);
        }
        catch (const std::exception& e) { return error(std::string("Tool failed: ") + e.what()); }
    }
}

int main()
{
    httplib::Server svr;
    svr.Get("/schema", [](const httplib::Request&, httplib::Response& res) {
        res.set_content(pooriayousefi::get_schema().dump(), "application/json");
    });
    svr.Post("/tools/call", [](const httplib::Request& req, httplib::Response& res) {
        json body = json::parse(req.body, nullptr, false);
        if (body.is_discarded()) { res.status = 400; res.set_content(R"({"error":"Invalid JSON"})", "application/json"); return; }
        const std::string name = body.value("name", "");
        const json args = body.value("arguments", json::object());
        res.set_content(pooriayousefi::handle_tool_call(args, name).dump(), "application/json");
    });

    std::cout << "FileServer running on http://127.0.0.1:4002\n";
    return svr.listen("127.0.0.1", 4002) ? 0 : 1;
}