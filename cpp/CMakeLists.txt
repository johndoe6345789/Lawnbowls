cmake_minimum_required(VERSION 3.15)
project(LawnBowls LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT WIN32)
    message(FATAL_ERROR "Lawn Bowls uses the Win32 API and only builds for Windows "
                        "(MSVC, MinGW-w64, or a MinGW cross-compiler).")
endif()

# WIN32 makes it a GUI app (WinMain, no console window).
add_executable(lawn_bowls WIN32 lawn_bowls.cpp)

target_link_libraries(lawn_bowls PRIVATE user32 gdi32)

if(MSVC)
    target_compile_options(lawn_bowls PRIVATE /W4 /permissive-)
    # Static CRT so the exe runs without the VC++ redistributable.
    set_property(TARGET lawn_bowls PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
else()
    target_compile_options(lawn_bowls PRIVATE -Wall -Wextra)
    # Static libgcc/libstdc++ so no MinGW DLLs are needed next to the exe.
    target_link_options(lawn_bowls PRIVATE -static)
endif()

# Default to an optimised build for single-config generators (Ninja, Makefiles).
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

install(TARGETS lawn_bowls RUNTIME DESTINATION .)
