#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <string>
#include <vector>
#include <random>
#include <sstream>
#include <iomanip>

using Json = nlohmann::json;

namespace pooriayousefi
{
    /**
     * @brief Tokenizer function.
     * Splits a string into a vector of tokens based on provided delimiters.
     */
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

    /**
     * @brief Generates a random UUID v4 string.
     *
     * FIX (efficiency): the previous version constructed a fresh
     * std::random_device + std::mt19937 on every single call. random_device
     * construction can be relatively expensive (it may open/read from an OS
     * entropy source such as /dev/urandom depending on platform/libstdc++
     * implementation), so paying that cost per request is wasteful for what's
     * meant to be a cheap utility call. A thread_local engine, seeded once per
     * worker thread from random_device, is reused across calls instead -
     * still safe under httplib's multi-threaded dispatch since each thread
     * gets its own independent engine instance.
     */
    inline std::string generate_uuid()
    {
        thread_local std::mt19937 gen{std::random_device{}()};
        std::uniform_int_distribution<> dis(0, 15);
        std::uniform_int_distribution<> dis2(8, 11);

        std::stringstream ss;
        ss << std::hex;
        for (int i = 0; i < 8; i++) ss << dis(gen);
        ss << "-";
        for (int i = 0; i < 4; i++) ss << dis(gen);
        ss << "-4"; // Version 4
        for (int i = 0; i < 3; i++) ss << dis(gen);
        ss << "-";
        ss << dis2(gen); // Variant
        for (int i = 0; i < 3; i++) ss << dis(gen);
        ss << "-";
        for (int i = 0; i < 12; i++) ss << dis(gen);

        return ss.str();
    }

    /**
     * @brief Scans forward from `start` (which must point at '{' or '[') and
     *        returns the index of the matching closing bracket, or npos.
     *
     * FIX (correctness): the previous balance-counter treated every '{'/'}'
     * and '['/']' character as structural, even ones that appear inside a
     * quoted JSON string value (e.g. "note": "use a { here"). That miscounts
     * the balance and can return a truncated or wrong span. This version
     * tracks whether the scan is currently inside a string literal (and
     * honors backslash-escapes within it) and only updates the bracket
     * balance while outside of one.
     */
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
                if (escaped) { escaped = false; }
                else if (c == '\\') { escaped = true; }
                else if (c == '"') { in_string = false; }
                continue;
            }

            if (c == '"') { in_string = true; continue; }
            if (c == open_char) balance++;
            else if (c == close_char) balance--;

            if (balance == 0) return i;
        }
        return std::string::npos;
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
                        "name": "tokenize_string",
                        "description": "Splits a given string into a list of tokens based on specified delimiter characters. Useful for parsing CSV data, counting words, or extracting specific parts of a string.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "sentence": { "type": "string", "description": "The text to be tokenized." },
                                "delimiters": { "type": "string", "description": "A string containing all characters to be treated as delimiters. For example, to split by space and comma, pass ' ,'." }
                            },
                            "required": ["sentence", "delimiters"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "count_words",
                        "description": "Counts the exact number of words in a string. Use this instead of guessing, as LLMs are notoriously bad at exact word counts.",
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
                        "description": "Extracts the first valid JSON object or array found within a block of messy text. Useful for parsing logs or scraping web pages where JSON is embedded in other text.",
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
                        "description": "Generates a random, unique UUID v4 string. Use this when you need to create unique identifiers for files, sessions, or data rows.",
                        "parameters": {
                            "type": "object",
                            "properties": {},
                            "required": []
                        }
                    }
                }
            ]
        )";
        return Json::parse(schema_str, nullptr, false);
    }

    /// Extracts a required string argument. Returns false and fills `error_out` on failure.
    bool get_required_string(const Json& args, const char* key, std::string& out, Json& error_out)
    {
        if (!args.contains(key) || !args[key].is_string())
        {
            error_out = {{"error", std::string("Missing or non-string '") + key + "' in arguments"}, {"is_error", true}};
            return false;
        }
        out = args[key].get<std::string>();
        return true;
    }

    /**
     * @brief Handles the execution of a requested tool.
     *
     * FIX (correctness/robustness): this function previously had no top-level
     * exception guard, unlike the other tool servers in this project. A
     * malformed argument (e.g. "sentence" passed as a number instead of a
     * string) would throw from nlohmann::json's .get<std::string>() and
     * escape uncaught into the httplib handler. It's now wrapped in try/catch
     * and every string argument is type-checked before extraction, so a bad
     * request always comes back as a JSON error instead of risking a crash.
     */
    Json handle_tool_call(const Json& args, const std::string& tool_name)
    {
        try
        {
            if (!args.is_object())
            {
                return {{"error", "Arguments must be a JSON object."}, {"is_error", true}};
            }

            if (tool_name == "tokenize_string")
            {
                std::string sentence, delimiters, err_json_unused;
                Json arg_error;
                if (!get_required_string(args, "sentence", sentence, arg_error)) return arg_error;
                if (!get_required_string(args, "delimiters", delimiters, arg_error)) return arg_error;

                auto tokens = tokenize(sentence, delimiters);

                Json tokens_array = Json::array();
                for (const auto& token : tokens)
                {
                    tokens_array.push_back(token);
                }

                return {{"result", tokens_array}, {"is_error", false}};
            }
            else if (tool_name == "count_words")
            {
                std::string text;
                Json arg_error;
                if (!get_required_string(args, "text", text, arg_error)) return arg_error;

                std::string delimiters = " \t\n\r"; // Default for word counting
                auto tokens = tokenize(text, delimiters);

                return {{"result", {{"word_count", tokens.size()}}}, {"is_error", false}};
            }
            else if (tool_name == "extract_json_from_text")
            {
                std::string text;
                Json arg_error;
                if (!get_required_string(args, "text", text, arg_error)) return arg_error;

                // FIX (correctness): previously only the FIRST '{' or '[' in the
                // text was ever tried. If that particular occurrence wasn't
                // actually the start of valid JSON (e.g. a stray brace earlier
                // in unrelated prose), the tool reported failure even though a
                // real JSON blob existed later in the text. This now keeps
                // trying subsequent candidate start positions until one parses.
                std::size_t search_from = 0;
                while (true)
                {
                    std::size_t start = text.find_first_of("{[", search_from);
                    if (start == std::string::npos)
                    {
                        return {{"error", "No valid JSON object or array found in text."}, {"is_error", true}};
                    }

                    std::size_t end = find_balanced_end(text, start);
                    if (end != std::string::npos)
                    {
                        std::string json_str = text.substr(start, end - start + 1);
                        Json parsed_json = Json::parse(json_str, nullptr, false);
                        if (!parsed_json.is_discarded())
                        {
                            return {{"result", parsed_json}, {"is_error", false}};
                        }
                    }

                    search_from = start + 1;
                }
            }
            else if (tool_name == "generate_uuid")
            {
                std::string uuid = generate_uuid();
                return {{"result", uuid}, {"is_error", false}};
            }

            return {{"error", "Unknown tool: " + tool_name}, {"is_error", true}};
        }
        catch (const std::exception& e)
        {
            return {{"error", std::string("Tool failed: ") + e.what()}, {"is_error", true}};
        }
    }
}

/**
 * @brief Main entry point for the Utility Server.
 */
int main()
{
    httplib::Server svr;

    // Safety net: guarantee a JSON response even if something unexpected
    // still throws (e.g. inside nlohmann::json's dump()).
    svr.set_exception_handler(
        [](const httplib::Request&, httplib::Response& res, const std::exception_ptr& ep)
        {
            std::string message = "Internal server error";
            try
            {
                if (ep) std::rethrow_exception(ep);
            }
            catch (const std::exception& e)
            {
                message = e.what();
            }
            res.status = 500;
            res.set_content(Json({{"error", message}, {"is_error", true}}).dump(), "application/json");
        });

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
                res.set_content(Json({{"error", "Invalid JSON payload"}}).dump(), "application/json");
                return;
            }

            std::string tool_name = req_body.value("name", "");
            Json arguments = req_body.value("arguments", Json::object());

            std::cout << "[UtilServer] Received call for tool: " << tool_name << std::endl;

            Json result = pooriayousefi::handle_tool_call(arguments, tool_name);
            res.set_content(result.dump(), "application/json");
        }
    );

    std::cout << "Utility Server running on http://localhost:4006" << std::endl;

    svr.listen("127.0.0.1", 4006);

    return 0;
}