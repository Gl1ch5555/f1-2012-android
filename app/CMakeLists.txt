# f1_2012 - ReXGlue Recompiled Project (F1 2012, Title ID: 434D0847)

cmake_minimum_required(VERSION 3.25)
project(f1_2012 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(generated/rexglue.cmake)

set(F1_2012_SOURCES
    src/main.cpp
)

if(NOT REXGLUE_ENTRYPOINT_GENERATED_SOURCES)
    list(APPEND F1_2012_SOURCES src/f1_2012_stub.cpp)
endif()

if(ANDROID)
    # SDLActivity resolves SDL_main from libmain.so.
    add_library(f1_2012 SHARED ${F1_2012_SOURCES})
    set_target_properties(f1_2012 PROPERTIES OUTPUT_NAME main)
elseif(WIN32)
    add_executable(f1_2012 WIN32 ${F1_2012_SOURCES})
else()
    add_executable(f1_2012 ${F1_2012_SOURCES})
endif()

rexglue_setup_target(f1_2012 GPU_PLUGINS xenos)

if(TARGET imgui)
    target_include_directories(f1_2012 PRIVATE
        $<TARGET_PROPERTY:imgui,INTERFACE_INCLUDE_DIRECTORIES>)
endif()
