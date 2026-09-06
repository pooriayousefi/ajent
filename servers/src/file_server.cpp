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
                {"type":"function","function":{"name":"get_file_info","description":"Returns metadata for a path at the specified absolute path.","parameters":{"type":"object","properties":{"path":{"type":"string","description":"The absolute path to inspect."}},"required":["path"]}}}
            ]
        )");
    }

    JSON error(std::string message)
    {
        return {{"error", std::move(message)}, {"is_error", true}};
    }

    JSON success(JSON value)
    {
        return {{"result", std::move(value)}, {"is_error", false}};
    }

    JSON handle_tool_call(const JSON& args, const std::string& tool_name)
    {
        JSON result;
        try
        {
            if (!args.is_object())
            {
                result = error("Arguments must be a JSON object.");
            }
            else
            {
                if (!args.contains("path") || !args["path"].is_string())
                {
                    result = error("Missing or invalid 'path'.");
                }
                else
                {
                    const std::string path_str = args["path"].get<std::string>();
                    fs::path path(path_str);
                    if (tool_name == "write_text_file" || tool_name == "append_text_file")
                    {
                        if (!args.contains("content") || !args["content"].is_string())
                        {
                            result = error("Missing or invalid 'content'.");
                        }
                        else
                        {
                            fs::path parent = path.parent_path();
                            if (!parent.empty())
                            {
                                std::error_code ec;
                                fs::create_directories(parent, ec);
                                if (ec)
                                {
                                    result = error("Failed to create parent directories: " + ec.message());
                                }
                                else
                                {
                                    std::ofstream out(path, tool_name == "append_text_file" ? (std::ios::app | std::ios::binary) : (std::ios::out | std::ios::binary | std::ios::trunc));
                                    if (!out)
                                    {
                                        result = error("Failed to open file for writing.");
                                    }
                                    else
                                    {
                                        out << args["content"].get<std::string>();
                                        result = success(tool_name == "append_text_file" ? "Content appended successfully." : "File written successfully.");
                                    }
                                }
                            }
                        }
                    }
                    else if (tool_name == "read_text_file")
                    {
                        if (!fs::is_regular_file(path))
                        {
                            result = error("Path is not a regular file.");
                        }
                        else
                        {
                            std::ifstream in(path, std::ios::binary);
                            if (!in)
                            {
                                result = error("Failed to open file for reading.");
                            }
                            else
                            {
                                result = success(std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>()));
                            }
                        }
                    }
                    else if (tool_name == "delete_file")
                    {
                        if (!fs::is_regular_file(path))
                        {
                            result = error("Path is not a regular file.");
                        }
                        else
                        {
                            std::error_code ec;
                            if (!fs::remove(path, ec) || ec)
                            {
                                result = error("Failed to delete file: " + ec.message());
                            }
                            else
                            {
                                result = success("File deleted successfully.");
                            }
                        }
                    }
                    else if (tool_name == "file_exists")
                    {
                        result = success(fs::exists(path));
                    }
                    else if (tool_name == "get_file_info")
                    {
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
                        result = success(info);
                    }
                    else
                    {
                        result = error("Unknown tool: " + tool_name);
                    }
                }
            }
        }
        catch (const std::exception& e)
        {
            result = error(std::string("Tool failed: ") + e.what());
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
            throw std::invalid_argument("Usage: file_server <host> <port>");
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
            }
        );

        std::cout << "file REST server running on http://" << host << ":" << port << "\n";
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