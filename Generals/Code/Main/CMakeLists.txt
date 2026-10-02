# GeneralsX @build Android port Core/Generals 02/10/2026 On Android the game
# is not a process of its own: SDL3's Java shell (Android/Generals) loads the
# game as a shared library named libmain.so and calls its SDL_main. Everywhere
# else the game remains a regular executable. Mirrors GeneralsMD/Code/Main.
if(ANDROID)
    add_library(g_generals SHARED)
else()
    add_executable(g_generals WIN32)
endif()

# Use a binary name that doesn't conflict with original game.
# GeneralsX @build BenderAI 15/02/2026 Linux branding update
if("${CMAKE_SYSTEM}" MATCHES "Windows")
    set_target_properties(g_generals PROPERTIES OUTPUT_NAME "generalsv${RTS_BUILD_OUTPUT_SUFFIX}")
elseif(ANDROID)
    # SDLActivity.getLibraries() loads "main" -> libmain.so (SDL convention)
    set_target_properties(g_generals PROPERTIES OUTPUT_NAME main)
else()
    # GeneralsX @build BenderAI 15/02/2026 Linux branding update
    set_target_properties(g_generals PROPERTIES OUTPUT_NAME GeneralsX)
endif()

# GeneralsX @build BenderAI 10/03/2026 Windows-specific libraries
if(WIN32)
    target_link_libraries(g_generals PRIVATE
        binkstub
        comctl32
        d3d8
        dinput8
        dxguid
        imm32
        milesstub
        vfw32
        winmm
    )
endif()

# Cross-platform core libraries
target_link_libraries(g_generals PRIVATE
    d3dx8
    g_gameengine
    g_gameenginedevice
    gi_always
)

# Linux SDL3 windowing/input
# GeneralsX @build BenderAI 11/02/2026
if(SAGE_USE_SDL3)
    target_link_libraries(g_generals PRIVATE sdl3lib)
endif()

# GeneralsX @feature Android port Core/Generals 02/10/2026 Android-only link
# set, mirroring GeneralsMD/Code/Main/CMakeLists.txt:
#
# adrenotools (cmake/adrenotools.cmake): custom Vulkan driver loading. Its
# TryLoadCustomVulkanDriver() must run before DXVK's separately-built .so
# dlopen()s the stock libvulkan.so, so it links into libmain.so itself.
#
# d3d8gles (Core/Libraries/Source/d3d8gles): the statically-linked D3D8 ->
# OpenGL ES 3.0 translation layer selected at runtime by the backend switch
# in dx8wrapper.cpp (Core's WW3D2 references Direct3DCreate8_GLES()), as an
# alternative to the dlopen'd DXVK D3D8 -> Vulkan path. Both backends ship;
# the engine picks one at D3D init.
if(ANDROID)
    target_link_libraries(g_generals PRIVATE adrenotools)
    target_link_libraries(g_generals PRIVATE d3d8gles)
endif()

# GeneralsX @feature Android port Core/Generals 02/10/2026 CI passes the GitHub
# Actions run number via -DANDROID_CI_BUILD_NUMBER (same convention as
# GeneralsMD/Code/Main) so a crash log can prove which build produced it.
# Local/non-CI builds don't pass the define, so default to 0.
if(NOT DEFINED ANDROID_CI_BUILD_NUMBER)
    set(ANDROID_CI_BUILD_NUMBER 0)
endif()

# TODO Originally referred to build host and user, replace with git info perhaps?
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/GeneratedVersion.h
"#pragma once

#define VERSION_LOCALBUILDNUM 0
#define VERSION_BUILDUSER \"\"
#define VERSION_BUILDLOC \"\"
#define ANDROID_CI_BUILD_NUMBER ${ANDROID_CI_BUILD_NUMBER}
"
)

# Based on original binary values for these variables.
if (IS_VS6_BUILD)
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/BuildVersion.h
"#pragma once

#define VERSION_MAJOR 1
#define VERSION_MINOR 7
#define VERSION_BUILDNUM 601
"
)
else()
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/BuildVersion.h
"#pragma once

#define VERSION_MAJOR 1
#define VERSION_MINOR 8
#define VERSION_BUILDNUM 601
"
)
endif()

if(MSVC)
    target_link_options(g_generals PRIVATE "/NODEFAULTLIB:libci.lib")
endif()

target_include_directories(g_generals PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_BINARY_DIR}
    ${CMAKE_BINARY_DIR}/Code/Libraries
)

target_sources(g_generals PRIVATE
    $<$<BOOL:${WIN32}>:WinMain.cpp>
    $<$<BOOL:${WIN32}>:WinMain.h>
    # Linux SDL3 entry point
    $<$<NOT:$<BOOL:${WIN32}>>:SDL3Main.cpp>
    # Linux stubs
    $<$<NOT:$<BOOL:${WIN32}>>:LinuxStubs.cpp>
)

# RC files are optional for MinGW builds
# Icon: LoadIcon() handles missing resource gracefully (uses default system icon)
# Manifest: DPI awareness metadata (nice to have but not essential)
# TYPELIB: Broken (0 bytes) and causes windres memory exhaustion errors
if(MSVC)
    # VS2005 and later adds default manifest, we need to turn it off to prevent conflict with custom manifest
    if(NOT IS_VS6_BUILD)
        target_link_options(g_generals PRIVATE
            "/MANIFEST:NO"
        )
    endif()

    target_sources(g_generals PRIVATE
        RTS.RC
    )
endif()

# Strip debug symbols to separate file for MinGW Release builds
# This creates generalsv${RTS_BUILD_OUTPUT_SUFFIX}.exe.debug (similar to MSVC .pdb files)
if(MINGW AND COMMAND add_debug_strip_target)
    add_debug_strip_target(g_generals)
endif()

# GeneralsX @bugfix Android port Core/Generals 02/10/2026 Hide the engine's
# global operator new/delete from libmain.so's dynamic symbol table via a
# linker version script. On Android all loaded libraries share a flat symbol
# namespace, so libmain.so's exported operator new/delete would interpose over
# the copies inside DXVK (libdxvk_d3d8.so), SDL3 and OpenAL -- and our
# zero-filling operator new corrupts DXVK's live free-list bookkeeping. This
# is the same fix (and rationale) GeneralsMD/Code/Main/CMakeLists.txt applies
# to the Zero Hour engine; keep the two in sync.
#
# Safe here specifically because RTS_GAMEMEMORY_ENABLE is OFF on Android
# (GameMemoryNull's operators are already malloc/free under the hood, so
# cross-library alloc/free stays consistent).
if(ANDROID)
    set(_gx_hide_alloc_map "${CMAKE_CURRENT_BINARY_DIR}/gx_hide_alloc.map")
    file(WRITE "${_gx_hide_alloc_map}" [=[
{
  global:
    *;
  local:
    _Znw*;
    _Zna*;
    _Zdl*;
    _Zda*;
};
]=])
    target_link_options(g_generals PRIVATE
        "-Wl,--version-script,${_gx_hide_alloc_map}"
    )
endif()
