cmake_minimum_required(VERSION 3.10)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_SOURCE_DIR}/CMake")

option(
    ENABLE_CCACHE
    "Should ccache be used?"
    OFF
)
message(STATUS "option ENABLE_CCACHE=" ${ENABLE_CCACHE})

if(ENABLE_CCACHE)
    find_program(CCACHE_FOUND ccache)
    if(CCACHE_FOUND)
        set_property(GLOBAL PROPERTY RULE_LAUNCH_COMPILE ${CCACHE_FOUND})
        set_property(GLOBAL PROPERTY RULE_LAUNCH_LINK ${CCACHE_FOUND})
    else(CCACHE_FOUND)
        message(WARNING "ccache was not found. Fall-back to default compiler.")
    endif(CCACHE_FOUND)
endif(ENABLE_CCACHE)

######## DEFAULT COMPILER FLAGS #############

set(CMAKE_CXX_STANDARD 14)
set(CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# this project includes all test subdirectories
project(joynr-tests)
add_subdirectory(dummyKeychain)
add_subdirectory(inter-language-test)
add_subdirectory(performance-test)
add_subdirectory(system-integration-test/sit-cpp-app)
