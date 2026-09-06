
#include <stdexcept>
#include <exception>
#include <iostream>
#include <thread>
#include <chrono>
#include <ranges>

// Entry point
int main()
{
    auto exit_code{ 0 };
    try
    {
        // Start coding here...
        exit_code = EXIT_SUCCESS;
    }
    catch (const std::exception &xxx)
    {
        std::cerr << xxx.what() << "\n\nProgram exits in 5 seconds: ";
        for (auto i : std::ranges::views::iota(1, 6))
        {
            std::this_thread::sleep_for(std::chrono::seconds(1));
            std::cout << (6 - i) << ' ';
        }
        std::cout << std::endl;
        exit_code = EXIT_FAILURE;
    }
    return exit_code;
}
        