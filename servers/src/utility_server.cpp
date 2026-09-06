#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <string>
#include <vector>
#include <random>
#include <sstream>
#include <iomanip>

using JSON = nlohmann::json;

namespace pooriayousefi
{
    inline std::vector<std::string> tokenize(
        const std::string &sentence,
        const std::string &delimiters
    )
    {
        std::vector<std::string> tokens{};
        tokens.reserve(16);

        auto last_pos = sentence.find_first_not_of(delimiters, 0);
        auto pos = sentence.find_first_of(delimiters, last_pos);

        while (pos != std::string::npos || last_pos != std::string::npos)
        {
            tokens.emplace_back(sentence.substr(last_pos, pos - last_pos));
            last_pos = sentence.find_first_not_of(delimiters, pos);
            pos = sentence.find_first_of(delimiters, last_pos);
        }

        return tokens;
    }

    inline std::string generate_uuid()
    {
        thread_local std::mt19937 gen{std::random_device{}()};
        std::uniform_int_distribution<> dis(0, 15);
        std::uniform_int_distribution<> dis2(8, 11);

        std::stringstream ss;
        ss << std::hex;
        for (int i = 0; i < 8; i++)
        {
            ss << dis(gen);
        }
        ss << "-";
        for (int i = 0; i < 4; i++)
        {
            ss << dis(gen);
        }
        ss << "-4";
        for (int i = 0; i < 3; i++)
        {
            ss << dis(gen);
        }
        ss << "-";
        ss << dis2(gen);
        for (int i = 0; i < 3; i++)
        {
            ss << dis(gen);
        }
        ss << "-";
        for (int i = 0; i < 12; i++)
        {
            ss << dis(gen);
        }

        return ss.str();
    }

    inline std::size_t find_balanced_end(const std::string& text, std::size_t start)
    {
        char open_char = text[start];
        char close_char = (open_char == '{') ? '}' : ']';

        int balance = 0;
        bool in_string = false;
        bool escaped = false;

        for (std::size_t i = start; i < text.length(); ++i)
        {
            char c = text[i];

            if (in_string)
            {
                if (escaped)
                {
                    escaped = false;
                }
                else if (c == '\\')
                {
                    escaped = true;
                }
                else if (c == '"')
                {
                    in_string = false;
                }
                continue;
            }

            if (c == '"')
            {
                in_string = true;
                continue;
            }
            if (c == open_char)
            {
                balance++;
            }
            else if (c == close_char)
            {
                balance--;
            }

            if (balance == 0)
            {
                return i;
            }
        }
        return std::string::npos;
    }

    JSON get_schema()
    {
        std::string schema_str = R"(
            [
                {
                    "type": "function",
                    "function": {
                        "name": "tokenize_string",
                        "description": "Splits a given string into a list of tokens based on specified delimiter characters.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "sentence": { "type": "string", "description": "The text to be tokenized." },
                                "delimiters": { "type": "string", "description": "A string containing all characters to be treated as delimiters." }
                            },
                            "required": ["sentence", "delimiters"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "count_words",
                        "description": "Counts the exact number of words in a string.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "text": { "type": "string", "description": "The text to count words in." }
                            },
                            "required": ["text"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "extract_json_from_text",
                        "description": "Extracts the first valid JSON object or array found within a block of messy text.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "text": { "type": "string", "description": "The raw text that may contain a JSON string." }
                            },
                            "required": ["text"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "generate_uuid",
                        "description": "Generates a random, unique UUID v4 string.",
                        "parameters": {
                            "type": "object",
                            "properties": {},
                            "required": []
                        }
                    }
                }
            ]
        )";
        return JSON::parse(schema_str, nullptr, false);
    }

    bool get_required_string(const JSON& args, const char* key, std::string& out, JSON& error_out)
    {
        auto result{ true };
        if (!args.contains(key) || !args[key].is_string())
        {
            error_out = {{"error", std::string("Missing or non-string '") + key + "' in arguments"}, {"is_error", true}};
            result = false;
        }
        else
        {
            out = args[key].get<std::string>();
        }
        return result;
    }

    JSON handle_tool_call(const JSON& args, const std::string& tool_name)
    {
        JSON result;
        try
        {
            if (!args.is_object())
            {
                result = {{"error", "Arguments must be a JSON object."}, {"is_error", true}};
            }
            else
            {
                if (tool_name == "tokenize_string")
                {
                    std::string sentence, delimiters, err_json_unused;
                    JSON arg_error;
                    if (!get_required_string(args, "sentence", sentence, arg_error))
                    {
                        result = arg_error;
                    }
                    else
                    {
                        if (!get_required_string(args, "delimiters", delimiters, arg_error))
                        {
                            result = arg_error;
                        }
                        else
                        {
                            auto tokens = tokenize(sentence, delimiters);

                            JSON tokens_array = JSON::array();
                            for (const auto& token : tokens)
                            {
                                tokens_array.push_back(token);
                            }

                            result = { {"result", tokens_array}, {"is_error", false} };
                        }                        
                    }                    
                }
                else if (tool_name == "count_words")
                {
                    std::string text;
                    JSON arg_error;
                    if (!get_required_string(args, "text", text, arg_error))
                    {
                        result = arg_error;
                    }
                    else
                    {
                        std::string delimiters = " \t\n\r";
                        auto tokens = tokenize(text, delimiters);

                        result = { {"result", {{"word_count", tokens.size()}}}, {"is_error", false} };
                    }                    
                }
                else if (tool_name == "extract_json_from_text")
                {
                    std::string text;
                    JSON arg_error;
                    if (!get_required_string(args, "text", text, arg_error))
                    {
                        result = arg_error;
                    }
                    else
                    {
                        std::size_t search_from = 0;
                        while (true)
                        {
                            std::size_t start = text.find_first_of("{[", search_from);
                            if (start == std::string::npos)
                            {
                                result = { {"error", "No valid JSON object or array found in text."}, {"is_error", true} };
                                break;
                            }

                            std::size_t end = find_balanced_end(text, start);
                            if (end != std::string::npos)
                            {
                                std::string json_str = text.substr(start, end - start + 1);
                                JSON parsed_json = JSON::parse(json_str, nullptr, false);
                                if (!parsed_json.is_discarded())
                                {
                                    result = { {"result", parsed_json}, {"is_error", false} };
                                }
                            }

                            search_from = start + 1;
                        }
                    }                    
                }
                else if (tool_name == "generate_uuid")
                {
                    std::string uuid = generate_uuid();
                    result = { {"result", uuid}, {"is_error", false} };
                }
                else
                {
                    result = { {"error", "Unknown tool: " + tool_name}, {"is_error", true} };
                }
            }            
        }
        catch (const std::exception& e)
        {
            result = {{"error", std::string("Tool failed: ") + e.what()}, {"is_error", true}};
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
            throw std::invalid_argument("Usage: utility_server <host> <port>");
        }

        host = argv[1];
        port = std::stoi(argv[2]);

        httplib::Server svr;

        svr.set_exception_handler(
            [](const httplib::Request&, httplib::Response& res, const std::exception_ptr& ep)
            {
                std::string message = "Internal server error";
                try
                {
                    if (ep)
                    {
                        std::rethrow_exception(ep);
                    }
                }
                catch (const std::exception& e)
                {
                    message = e.what();
                }
                res.status = 500;
                res.set_content(JSON({{"error", message}, {"is_error", true}}).dump(), "application/json");
            }
        );

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
                    res.set_content(JSON({{"error", "Invalid json payload"}}).dump(), "application/json");
                    return;
                }

                std::string tool_name = req_body.value("name", "");
                JSON arguments = req_body.value("arguments", JSON::object());

                std::cout << "[utility REST server] Received call for tool: " << tool_name << std::endl;

                JSON result = pooriayousefi::handle_tool_call(arguments, tool_name);
                res.set_content(result.dump(), "application/json");
            }
        );

        std::cout << "utility REST server running on http://" << host << ":" << port << std::endl;
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