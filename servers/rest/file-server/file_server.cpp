#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <system_error>

using JSON = nlohmann::json;
namespace fs = std::filesystem;

namespace pooriayousefi
{
    JSON get_schema()
    {
        return JSON::parse(R"(
            [
                {"type":"function","function":{"name":"write_text_file","description":"Writes text content to a file at the specified absolute path. Creates parent directories if needed. Overwrites if file exists.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path."},"content":{"type":"string","description":"The text content to write."}},"required":["path","content"]}}},
                {"type":"function","function":{"name":"read_text_file","description":"Reads text content from a regular file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to read."}},"required":["path"]}}},
                {"type":"function","function":{"name":"append_text_file","description":"Appends text content to a file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to append to."},"content":{"type":"string","description":"The text content to add."}},"required":["path","content"]}}},
                {"type":"function","function":{"name":"delete_file","description":"Deletes a regular file at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute file path to delete."}},"required":["path"]}}},
                {"type":"function","function":{"name":"file_exists","description":"Checks whether a path exists at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to check."}},"required":["path"]}}},
                {"type":"function","function":{"name":"get_file_info","description":"Returns metadata for a path at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to inspect."}},"required":["path"]}}},
                {"type":"function","function":{"name":"copy_file_overwrite_existing","description":"Copies a file from a source path to a destination path, overwriting the destination file if it already exists.","parameters":{"type":"object","properties":{"source":{"type":"string","description":"The absolute path of the file to copy."},"destination":{"type":"string","description":"The absolute path of the destination file."}},"required":["source","destination"]}}},
                {"type":"function","function":{"name":"copy_file_skip_existing","description":"Copies a file from a source path to a destination path only if the destination file does not already exist. Fails if the destination exists.","parameters":{"type":"object","properties":{"source":{"type":"string","description":"The absolute path of the file to copy."},"destination":{"type":"string","description":"The absolute path of the destination file."}},"required":["source","destination"]}}}
            ]
        )");
    }

    JSON error(std::string message) { return {{"error", std::move(message)}, {"is_error", true}}; }
    JSON success(JSON value) { return {{"result", std::move(value)}, {"is_error", false}}; }

    JSON handle_tool_call(const JSON& args, const std::string& tool_name)
    {
        try 
        {
            if (!args.is_object()) return error("Arguments must be a JSON object.");
            
            if (tool_name == "write_text_file" || tool_name == "append_text_file") 
            {
                if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
                if (!args.contains("content") || !args["content"].is_string()) return error("Missing or invalid 'content'.");
                
                fs::path path(args["path"].get<std::string>());
                fs::path parent = path.parent_path();
                std::error_code ec;
                
                if (!parent.empty()) 
                {
                    fs::create_directories(parent, ec);
                    if (ec) return error("Failed to create parent directories: " + ec.message());
                }
                
                std::ofstream out(path, tool_name == "append_text_file" ? (std::ios::app | std::ios::binary) : (std::ios::out | std::ios::binary | std::ios::trunc));
                if (!out) return error("Failed to open file for writing.");
                
                out << args["content"].get<std::string>();
                return success(tool_name == "append_text_file" ? "Content appended successfully." : "File written successfully.");
            } 
            else if (tool_name == "read_text_file") 
            {
                if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
                fs::path path(args["path"].get<std::string>());
                
                if (!fs::is_regular_file(path)) return error("Path is not a regular file.");
                
                std::ifstream in(path, std::ios::binary);
                if (!in) return error("Failed to open file for reading.");
                
                return success(std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>()));
            } 
            else if (tool_name == "delete_file") 
            {
                if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
                fs::path path(args["path"].get<std::string>());
                
                if (!fs::is_regular_file(path)) return error("Path is not a regular file.");
                
                std::error_code ec;
                if (!fs::remove(path, ec) || ec) return error("Failed to delete file: " + ec.message());
                return success("File deleted successfully.");
            } 
            else if (tool_name == "file_exists") 
            {
                if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
                fs::path path(args["path"].get<std::string>());
                return success(fs::exists(path));
            } 
            else if (tool_name == "get_file_info") 
            {
                if (!args.contains("path") || !args["path"].is_string()) return error("Missing or invalid 'path'.");
                std::string path_str = args["path"].get<std::string>();
                fs::path path(path_str);
                
                std::error_code ec;
                bool exists = fs::exists(path, ec);
                JSON info = {
                    {"path", path_str},
                    {"exists", exists},
                    {"is_directory", exists && fs::is_directory(path)},
                    {"is_regular_file", exists && fs::is_regular_file(path)}
                };
                if (exists && fs::is_regular_file(path)) 
                {
                    info["size_bytes"] = fs::file_size(path);
                }
                return success(info);
            }
            else if (tool_name == "copy_file_overwrite_existing" || tool_name == "copy_file_skip_existing") 
            {
                if (!args.contains("source") || !args["source"].is_string()) return error("Missing or invalid 'source'.");
                if (!args.contains("destination") || !args["destination"].is_string()) return error("Missing or invalid 'destination'.");
                
                fs::path src(args["source"].get<std::string>());
                fs::path dest(args["destination"].get<std::string>());
                
                if (!fs::is_regular_file(src)) return error("Source path is not a regular file.");
                
                std::error_code ec;
                fs::copy_options options = (tool_name == "copy_file_overwrite_existing") 
                    ? fs::copy_options::overwrite_existing 
                    : fs::copy_options::skip_existing;
                
                bool copied = fs::copy_file(src, dest, options, ec);
                
                if (ec) return error("Failed to copy file: " + ec.message());
                
                if (!copied && tool_name == "copy_file_skip_existing" && fs::exists(dest)) 
                {
                    return error("Destination file already exists. Copy skipped.");
                }
                else if (!copied) 
                {
                    return error("Failed to copy file for an unknown reason.");
                }
                
                return success("File copied successfully from " + src.string() + " to " + dest.string());
            }
            return error("Unknown tool: " + tool_name);
        } 
        catch (const std::exception& e) 
        {
            return error(std::string("Tool failed: ") + e.what());
        }
    }
}

int main(int argc, char* argv[])
{
    std::string host = "127.0.0.1";
    int port = 0;
    try 
    {
        if (argc != 3) throw std::invalid_argument("Usage: file_server <host> <port>");
        host = argv[1];
        port = std::stoi(argv[2]);

        httplib::Server svr;
        svr.Get("/schema", [](const httplib::Request&, httplib::Response& res) {
            res.set_content(pooriayousefi::get_schema().dump(), "application/json");
        });
        svr.Post("/tools/call", [](const httplib::Request& req, httplib::Response& res) {
            JSON body = JSON::parse(req.body, nullptr, false);
            if (body.is_discarded()) 
            {
                res.status = 400;
                res.set_content(R"({"error":"Invalid json"})", "application/json");
                return;
            }
            const std::string name = body.value("name", "");
            const JSON args = body.value("arguments", JSON::object());

            std::cout << "[file REST server] Received call for tool: " << name << std::endl;
            res.set_content(pooriayousefi::handle_tool_call(args, name).dump(), "application/json");
        });

        std::cout << "file REST server running on http://" << host << ":" << port << "\n";
        svr.listen(host, port);
    } 
    catch(const std::exception& e) 
    {
        std::cerr << e.what() << '\n';
        return EXIT_FAILURE;
    }
    return EXIT_SUCCESS;
}