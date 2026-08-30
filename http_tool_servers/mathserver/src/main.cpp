#include "cpp-httplib/httplib.h"
#include "nlohmann/json.hpp"

#include <iostream>
#include <string>
#include <vector>
#include <random>
#include <numeric>
#include <algorithm>
#include <cmath>
#include <optional>
#include <limits>

using Json = nlohmann::json;

namespace pooriayousefi
{
    /// Cap on how many random numbers a single request can generate, so a
    /// malicious/careless "count" value can't force an unbounded allocation
    /// and response size.
    constexpr int MAX_RANDOM_COUNT = 100'000;

    /// Cap on the size of the "numbers" array accepted by calculate_statistics,
    /// for the same reason.
    constexpr std::size_t MAX_STATS_NUMBERS = 1'000'000;

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
                        "name": "generate_random_number",
                        "description": "Generates random numbers using standard C++ random distributions. Useful for simulations and non-security-sensitive tasks, simulations, UUID generation, or games.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "distribution": { "type": "string", "description": "The distribution to use.", "enum": ["uniform_int", "uniform_real", "normal", "bernoulli"] },
                                "a": { "type": "number", "description": "For uniform distributions: the minimum value. For normal distribution: the mean. For bernoulli: the probability p. Defaults to 0." },
                                "b": { "type": "number", "description": "For uniform distributions: the maximum value. For normal distribution: the standard deviation (must be >= 0). Defaults to 100 for uniform, 1.0 for normal." },
                                "count": { "type": "integer", "description": "How many random numbers to generate. Defaults to 1, capped at 100000." }
                            },
                            "required": ["distribution"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "calculate_statistics",
                        "description": "Calculates exact statistical metrics for a list of numbers. LLMs are bad at computing mean, median, variance, and standard deviation internally, so use this tool for exact results.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "numbers": { "type": "array", "items": { "type": "number" }, "description": "The array of numbers to analyze." }
                            },
                            "required": ["numbers"]
                        }
                    }
                },
                {
                    "type": "function",
                    "function": {
                        "name": "arithmetic_operation",
                        "description": "Performs exact arithmetic on two numbers. Use this to avoid LLM math hallucinations, especially with large numbers or floating point precision.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "operation": { "type": "string", "description": "The operation to perform.", "enum": ["add", "subtract", "multiply", "divide", "modulo", "power"] },
                                "a": { "type": "number", "description": "The first operand." },
                                "b": { "type": "number", "description": "The second operand." }
                            },
                            "required": ["operation", "a", "b"]
                        }
                    }
                }
            ]
        )";
        return Json::parse(schema_str, nullptr, false);
    }
    
    /// Reads a numeric argument, returning std::nullopt if present but not a
    /// number (instead of letting Json::value<T> throw a type_error), or the
    /// given default if absent.
    std::optional<double> get_number(const Json& args, const char* key, double fallback, bool* was_present = nullptr)
    {
        if (!args.contains(key))
        {
            if (was_present) *was_present = false;
            return fallback;
        }
        if (was_present) *was_present = true;
        if (!args[key].is_number()) return std::nullopt;
        return args[key].get<double>();
    }

    /**
     * @brief Handles the execution of a requested tool.
     *
     * FIX: this function had no top-level exception guard, and pulled
     * arguments via Json::value<T>(key, default) which throws a type_error
     * if the key is present but holds the wrong JSON type (e.g. "a" sent as
     * a string) - unlike a missing key, which value() handles fine. A bad
     * request from the LLM could crash the handler. Everything is now
     * type-checked before extraction and the whole function is wrapped in
     * try/catch as a second line of defense.
     */
    Json handle_tool_call(const Json& args, const std::string& tool_name)
    {
        try
        {
            if (!args.is_object())
            {
                return {{"error", "Arguments must be a JSON object."}, {"is_error", true}};
            }

            if (tool_name == "generate_random_number")
            {
                std::string dist = args.value("distribution", "uniform_int");

                int count = 1;
                if (args.contains("count"))
                {
                    if (!args["count"].is_number_integer())
                        return {{"error", "'count' must be an integer."}, {"is_error", true}};
                    count = args["count"].get<int>();
                }
                if (count < 1) count = 1;
                if (count > MAX_RANDOM_COUNT)
                {
                    return {{"error", "'count' exceeds the maximum of " + std::to_string(MAX_RANDOM_COUNT) + "."}, {"is_error", true}};
                }

                // FIX (efficiency): a fresh std::random_device + std::mt19937_64
                // was constructed on every single call, which is unnecessarily
                // expensive (random_device may read from an OS entropy source
                // per construction). A thread_local engine, seeded once per
                // worker thread, is reused across calls; this is safe under
                // httplib's multi-threaded dispatch since each thread gets its
                // own independent engine.
                thread_local std::mt19937_64 gen{std::random_device{}()};

                Json numbers = Json::array();

                auto a_opt = get_number(args, "a", 0.0);
                auto b_opt = get_number(args, "b", (dist == "normal") ? 1.0 : 100.0);
                if (!a_opt || !b_opt)
                {
                    return {{"error", "'a' and 'b' must be numbers."}, {"is_error", true}};
                }

                if (dist == "uniform_int")
                {
                    long long a = static_cast<long long>(*a_opt);
                    long long b = static_cast<long long>(*b_opt);
                    if (a > b) std::swap(a, b);
                    std::uniform_int_distribution<long long> dis(a, b);
                    for (int i = 0; i < count; ++i) numbers.push_back(dis(gen));
                }
                else if (dist == "uniform_real")
                {
                    double a = *a_opt;
                    double b = *b_opt;
                    if (a > b) std::swap(a, b);
                    std::uniform_real_distribution<double> dis(a, b);
                    // FIX: previously round-tripped each value through an
                    // ostringstream (setprecision(15)) and back via std::stod
                    // for no real benefit - nlohmann::json already serializes
                    // doubles precisely. The round trip added cost and a
                    // (rare) extra failure mode if stod ever threw.
                    for (int i = 0; i < count; ++i) numbers.push_back(dis(gen));
                }
                else if (dist == "normal")
                {
                    double mean = *a_opt;
                    double stddev = *b_opt;
                    if (stddev < 0.0)
                    {
                        // FIX: std::normal_distribution requires stddev >= 0;
                        // violating that is undefined behavior, not a thrown
                        // exception. Validate explicitly instead.
                        return {{"error", "Standard deviation ('b') must be >= 0."}, {"is_error", true}};
                    }
                    std::normal_distribution<double> dis(mean, stddev);
                    for (int i = 0; i < count; ++i) numbers.push_back(dis(gen));
                }
                else if (dist == "bernoulli")
                {
                    double p = *a_opt;
                    if (args.value("a", 0.5) == 0.5 && !args.contains("a")) p = 0.5;
                    if (p < 0.0 || p > 1.0)
                    {
                        // FIX: std::bernoulli_distribution requires 0 <= p <= 1;
                        // out-of-range p is undefined behavior. Validate explicitly.
                        return {{"error", "Probability 'a' must be between 0 and 1."}, {"is_error", true}};
                    }
                    std::bernoulli_distribution dis(p);
                    for (int i = 0; i < count; ++i) numbers.push_back(dis(gen) ? 1 : 0);
                }
                else
                {
                    return {{"error", "Unknown distribution: " + dist}, {"is_error", true}};
                }

                if (count == 1)
                {
                    return {{"result", numbers[0]}, {"is_error", false}};
                }
                return {{"result", numbers}, {"is_error", false}};
            }
            else if (tool_name == "calculate_statistics")
            {
                if (!args.contains("numbers") || !args["numbers"].is_array())
                {
                    return {{"error", "Missing or invalid 'numbers' array in arguments"}, {"is_error", true}};
                }
                if (args["numbers"].size() > MAX_STATS_NUMBERS)
                {
                    return {{"error", "'numbers' array exceeds the maximum of " + std::to_string(MAX_STATS_NUMBERS) + " elements."}, {"is_error", true}};
                }

                std::vector<double> nums;
                nums.reserve(args["numbers"].size());
                for (const auto& val : args["numbers"])
                {
                    if (val.is_number())
                    {
                        nums.push_back(val.get<double>());
                    }
                }

                if (nums.empty())
                {
                    return {{"error", "The numbers array is empty (or contained no numeric values)."}, {"is_error", true}};
                }

                std::size_t n = nums.size();
                double sum = std::accumulate(nums.begin(), nums.end(), 0.0);
                double mean = sum / static_cast<double>(n);

                auto [min_it, max_it] = std::minmax_element(nums.begin(), nums.end());
                double min_val = *min_it;
                double max_val = *max_it;

                std::vector<double> sorted_nums = nums;
                std::sort(sorted_nums.begin(), sorted_nums.end());
                double median = (n % 2 == 0) ?
                                (sorted_nums[n / 2 - 1] + sorted_nums[n / 2]) / 2.0 :
                                sorted_nums[n / 2];

                double sq_sum = 0.0;
                for (double x : nums) sq_sum += (x - mean) * (x - mean);
                double variance = sq_sum / static_cast<double>(n);
                double std_dev = std::sqrt(variance);

                Json stats = {
                    {"count", n},
                    {"sum", sum},
                    {"mean", mean},
                    {"median", median},
                    {"min", min_val},
                    {"max", max_val},
                    {"variance", variance},
                    {"standard_deviation", std_dev}
                };

                return {{"result", stats}, {"is_error", false}};
            }
            else if (tool_name == "arithmetic_operation")
            {
                if (!args.contains("operation") || !args.contains("a") || !args.contains("b"))
                {
                    return {{"error", "Missing 'operation', 'a', or 'b' in arguments"}, {"is_error", true}};
                }
                if (!args["operation"].is_string() || !args["a"].is_number() || !args["b"].is_number())
                {
                    return {{"error", "'operation' must be a string, and 'a'/'b' must be numbers."}, {"is_error", true}};
                }

                std::string op = args["operation"].get<std::string>();
                double a = args["a"].get<double>();
                double b = args["b"].get<double>();
                double result = 0.0;

                if (op == "add") result = a + b;
                else if (op == "subtract") result = a - b;
                else if (op == "multiply") result = a * b;
                else if (op == "divide")
                {
                    if (b == 0.0) return {{"error", "Division by zero"}, {"is_error", true}};
                    result = a / b;
                }
                else if (op == "modulo")
                {
                    if (b == 0.0) return {{"error", "Modulo by zero"}, {"is_error", true}};
                    result = std::fmod(a, b);
                }
                else if (op == "power") result = std::pow(a, b);
                else return {{"error", "Unknown operation: " + op}, {"is_error", true}};

                // FIX: JSON (per RFC 8259) has no representation for NaN or
                // Infinity. nlohmann::json silently serializes such doubles as
                // `null` on dump(), which previously meant e.g. pow(0, -1) or
                // an overflowing power/modulo would come back to the LLM as an
                // opaque "null" result with no explanation. Surface it as an
                // explicit error instead.
                if (!std::isfinite(result))
                {
                    return {{"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true}};
                }

                return {{"result", result}, {"is_error", false}};
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
 * @brief Main entry point for the Math Server.
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
                // FIX: was "application/Json" (typo, wrong case) - the header
                // is a MIME type, not a JSON literal.
                res.set_content(Json({{"error", "Invalid JSON payload"}}).dump(), "application/json");
                return;
            }

            std::string tool_name = req_body.value("name", "");
            Json arguments = req_body.value("arguments", Json::object());

            std::cout << "[MathServer] Received call for tool: " << tool_name << std::endl;

            Json result = pooriayousefi::handle_tool_call(arguments, tool_name);
            res.set_content(result.dump(), "application/json");
        }
    );

    std::cout << "Math Server running on http://localhost:4003" << std::endl;

    svr.listen("127.0.0.1", 4003);

    return 0;
}