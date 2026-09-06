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

using JSON = nlohmann::json;

namespace pooriayousefi
{
    constexpr int MAX_RANDOM_COUNT = 100'000;
    constexpr std::size_t MAX_STATS_NUMBERS = 1'000'000;

    JSON get_schema()
    {
        std::string schema_str = R"(
            [
                {
                    "type": "function",
                    "function": {
                        "name": "generate_random_number",
                        "description": "Generates random numbers using standard C++ random distributions.",
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
                        "description": "Calculates exact statistical metrics for a list of numbers.",
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
                        "description": "Performs exact arithmetic on two numbers.",
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
        return JSON::parse(schema_str, nullptr, false);
    }
    
    std::optional<double> get_number(const JSON& args, const char* key, double fallback, bool* was_present = nullptr)
    {
        std::optional<double> result{ std::nullopt };
        if (!args.contains(key))
        {
            if (was_present)
            {
                *was_present = false;
            }
            result = fallback;
        }
        else
        {
            if (was_present)
            {
                *was_present = true;
            }
            if (!args[key].is_number())
            {
                result = std::nullopt;
            }
            else
            {
                result = args[key].get<double>();
            }
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
                if (tool_name == "generate_random_number")
                {
                    std::string dist = args.value("distribution", "uniform_int");

                    int count = 1;
                    if (args.contains("count"))
                    {
                        if (!args["count"].is_number_integer())
                        {
                            result = { {"error", "'count' must be an integer."}, {"is_error", true} };
                        }
                        else
                        {
                            count = args["count"].get<int>();
                            if (count < 1)
                            {
                                count = 1;
                            }
                            if (count > MAX_RANDOM_COUNT)
                            {
                                result = { {"error", "'count' exceeds the maximum of " + std::to_string(MAX_RANDOM_COUNT) + "."}, {"is_error", true} };
                            }
                            else
                            {
                                thread_local std::mt19937_64 gen{ std::random_device{}() };
                                JSON numbers = JSON::array();
                                auto a_opt = get_number(args, "a", 0.0);
                                auto b_opt = get_number(args, "b", (dist == "normal") ? 1.0 : 100.0);
                                if (!a_opt || !b_opt)
                                {
                                    result = { {"error", "'a' and 'b' must be numbers."}, {"is_error", true} };
                                }
                                else
                                {
                                    if (dist == "uniform_int")
                                    {
                                        long long a = static_cast<long long>(*a_opt);
                                        long long b = static_cast<long long>(*b_opt);
                                        if (a > b)
                                        {
                                            std::swap(a, b);
                                        }
                                        std::uniform_int_distribution<long long> dis(a, b);
                                        for (int i = 0; i < count; ++i)
                                        {
                                            numbers.push_back(dis(gen));
                                        }
                                    }
                                    else if (dist == "uniform_real")
                                    {
                                        double a = *a_opt;
                                        double b = *b_opt;
                                        if (a > b)
                                        {
                                            std::swap(a, b);
                                        }
                                        std::uniform_real_distribution<double> dis(a, b);
                                        for (int i = 0; i < count; ++i)
                                        {
                                            numbers.push_back(dis(gen));
                                        }
                                    }
                                    else if (dist == "normal")
                                    {
                                        double mean = *a_opt;
                                        double stddev = *b_opt;
                                        if (stddev < 0.0)
                                        {
                                            result = { {"error", "Standard deviation ('b') must be >= 0."}, {"is_error", true} };
                                        }
                                        else
                                        {
                                            std::normal_distribution<double> dis(mean, stddev);
                                            for (int i = 0; i < count; ++i)
                                            {
                                                numbers.push_back(dis(gen));
                                            }
                                        }
                                    }
                                    else if (dist == "bernoulli")
                                    {
                                        double p = *a_opt;
                                        if (args.value("a", 0.5) == 0.5 && !args.contains("a"))
                                        {
                                            p = 0.5;
                                        }
                                        if (p < 0.0 || p > 1.0)
                                        {
                                            result = { {"error", "Probability 'a' must be between 0 and 1."}, {"is_error", true} };
                                        }
                                        else
                                        {
                                            std::bernoulli_distribution dis(p);
                                            for (int i = 0; i < count; ++i)
                                            {
                                                numbers.push_back(dis(gen) ? 1 : 0);
                                            }
                                        }
                                    }
                                    else
                                    {
                                        result = { {"error", "Unknown distribution: " + dist}, {"is_error", true} };
                                    }

                                    if (count == 1)
                                    {
                                        result = { {"result", numbers[0]}, {"is_error", false} };
                                    }
                                    result = { {"result", numbers}, {"is_error", false} };
                                }                                
                            }
                        }
                    }
                }
                else if (tool_name == "calculate_statistics")
                {
                    if (!args.contains("numbers") || !args["numbers"].is_array())
                    {
                        result = { {"error", "Missing or invalid 'numbers' array in arguments"}, {"is_error", true} };
                    }
                    else if (args["numbers"].size() > MAX_STATS_NUMBERS)
                    {
                        result = { {"error", "'numbers' array exceeds the maximum of " + std::to_string(MAX_STATS_NUMBERS) + " elements."}, {"is_error", true} };
                    }
                    else
                    {
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
                            result = { {"error", "The numbers array is empty (or contained no numeric values)."}, {"is_error", true} };
                        }
                        else
                        {
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
                            for (double x : nums)
                            {
                                sq_sum += (x - mean) * (x - mean);
                            }
                            double variance = sq_sum / static_cast<double>(n);
                            double std_dev = std::sqrt(variance);

                            JSON stats = {
                                {"count", n},
                                {"sum", sum},
                                {"mean", mean},
                                {"median", median},
                                {"min", min_val},
                                {"max", max_val},
                                {"variance", variance},
                                {"standard_deviation", std_dev}
                            };
                            result = { {"result", stats}, {"is_error", false} };
                        }                        
                    }                    
                }
                else if (tool_name == "arithmetic_operation")
                {
                    if (!args.contains("operation") || !args.contains("a") || !args.contains("b"))
                    {
                        result = { {"error", "Missing 'operation', 'a', or 'b' in arguments"}, {"is_error", true} };
                    }
                    else if (!args["operation"].is_string() || !args["a"].is_number() || !args["b"].is_number())
                    {
                        result = { {"error", "'operation' must be a string, and 'a'/'b' must be numbers."}, {"is_error", true} };
                    }
                    else
                    {
                        std::string op = args["operation"].get<std::string>();
                        double a = args["a"].get<double>();
                        double b = args["b"].get<double>();
                        double result_value = 0.0;

                        if (op == "add")
                        {
                            result_value = a + b;
                            if (!std::isfinite(result_value))
                            {
                                result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                            }
                            else
                            {
                                result = { {"result", result_value}, {"is_error", false} };
                            }
                        }
                        else if (op == "subtract")
                        {
                            result_value = a - b;
                            if (!std::isfinite(result_value))
                            {
                                result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                            }
                            else
                            {
                                result = { {"result", result_value}, {"is_error", false} };
                            }
                        }
                        else if (op == "multiply")
                        {
                            result_value = a * b;
                            if (!std::isfinite(result_value))
                            {
                                result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                            }
                            else
                            {
                                result = { {"result", result_value}, {"is_error", false} };
                            }
                        }
                        else if (op == "divide")
                        {
                            if (b == 0.0)
                            {
                                result = { {"error", "Division by zero"}, {"is_error", true} };
                            }
                            else
                            {
                                result_value = a / b;
                                if (!std::isfinite(result_value))
                                {
                                    result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                                }
                                else
                                {
                                    result = { {"result", result_value}, {"is_error", false} };
                                }
                            }
                        }
                        else if (op == "modulo")
                        {
                            if (b == 0.0)
                            {
                                result = { {"error", "Modulo by zero"}, {"is_error", true} };
                            }
                            else
                            {
                                result_value = std::fmod(a, b);
                                if (!std::isfinite(result_value))
                                {
                                    result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                                }
                                else
                                {
                                    result = { {"result", result_value}, {"is_error", false} };
                                }
                            }
                        }
                        else if (op == "power")
                        {
                            result_value = std::pow(a, b);
                            if (!std::isfinite(result_value))
                            {
                                result = { {"error", "Result is not a finite number (overflow, NaN, or infinity)."}, {"is_error", true} };
                            }
                            else
                            {
                                result = { {"result", result_value}, {"is_error", false} };
                            }
                        }
                        else
                        {
                            result = { {"error", "Unknown operation: " + op}, {"is_error", true} };
                        }
                    }                    
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
            throw std::invalid_argument("Usage: math_server <host> <port>");
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

                std::cout << "[math REST server] Received call for tool: " << tool_name << std::endl;

                JSON result = pooriayousefi::handle_tool_call(arguments, tool_name);
                res.set_content(result.dump(), "application/json");
            }
        );

        std::cout << "math REST server running on http://" << host << ":" << port << std::endl;
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