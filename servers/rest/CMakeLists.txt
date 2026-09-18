cmake_minimum_required(VERSION 3.15)
project(ajent_tool_servers CXX)

# Require C++23
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find Threads (cpp-httplib requires pthread on Linux/macOS)
set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)

# Include directories for cpp-httplib and nlohmann/json
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# Macro to simplify adding a server executable
macro(add_ajent_server name source_file)
    add_executable(${name} ${source_file})
    target_link_libraries(${name} PRIVATE Threads::Threads)
endmacro()

# Build targets for each server
add_ajent_server(math_server math-server/math_server.cpp)
add_ajent_server(directory_server directory-server/directory_server.cpp)
add_ajent_server(file_server file-server/file_server.cpp)
add_ajent_server(shell_server shell-server/shell_server.cpp)
add_ajent_server(utility_server utility-server/utility_server.cpp)