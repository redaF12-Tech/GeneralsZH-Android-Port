# GeneralsX @build Android port 02/10/2026 vcpkg toolchain resolution
# CMake toolchain file that locates a vcpkg installation and hands over to
# vcpkg's own toolchain file.
#
# Why this indirection exists: every preset that uses vcpkg spells its
# toolchain as
#
#     CMAKE_TOOLCHAIN_FILE: "$env{VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake"
#
# CMake expands a missing environment variable to the empty string, so an
# unexported VCPKG_ROOT does not produce "VCPKG_ROOT is not set" -- it produces
# the absolute path "/scripts/buildsystems/vcpkg.cmake", and CMake stops with
#
#     CMake Error: Could not find toolchain file: /scripts/buildsystems/vcpkg.cmake
#
# which names a path nobody typed and says nothing about the variable that is
# actually missing. Resolving here instead means the same situation produces a
# message that lists every location searched and the one command that fixes it.
#
# Search order (first hit wins):
#   1. VCPKG_ROOT, as a CMake variable (someone passed -DVCPKG_ROOT=...)
#   2. $ENV{VCPKG_ROOT}, the documented way
#   3. $ENV{VCPKG_INSTALLATION_ROOT}, set by vcpkg's own GitHub Actions action
#   4. $ENV{HOME}/vcpkg, the clone location used by docs/port/ANDROID_PORT.md
#   5. /opt/vcpkg, where the sandboxed and CI builds put it
#
# A vcpkg checkout is identified by ".vcpkg-root", the marker file vcpkg
# itself uses, so a directory that merely happens to be named "vcpkg" is not
# accepted as one.
#
# This file is included as CMAKE_TOOLCHAIN_FILE. vcpkg's own file detects that
# it is being included rather than being the toolchain file itself and returns
# early after doing its work (vcpkg.cmake: "if(VCPKG_TOOLCHAIN) return()"), so
# including it from here is the supported shape, not a trick.

set(GX_VCPKG_ROOT "")

foreach(_gx_candidate
        "${VCPKG_ROOT}"
        "$ENV{VCPKG_ROOT}"
        "$ENV{VCPKG_INSTALLATION_ROOT}"
        "$ENV{HOME}/vcpkg"
        "/opt/vcpkg")
    if(_gx_candidate AND EXISTS "${_gx_candidate}/.vcpkg-root"
       AND EXISTS "${_gx_candidate}/scripts/buildsystems/vcpkg.cmake")
        set(GX_VCPKG_ROOT "${_gx_candidate}")
        break()
    endif()
endforeach()

if(NOT GX_VCPKG_ROOT)
    # Name every candidate, and show a value only where one is set: printing an
    # unset variable's empty expansion just yields a run of bare commas, which
    # reads like a formatting bug rather than a list.
    set(_gx_searched "VCPKG_ROOT (CMake variable)")
    foreach(_gx_name VCPKG_ROOT VCPKG_INSTALLATION_ROOT)
        if(DEFINED ENV{${_gx_name}} AND NOT "$ENV{${_gx_name}}" STREQUAL "")
            string(APPEND _gx_searched ", \$${_gx_name}=$ENV{${_gx_name}}")
        else()
            string(APPEND _gx_searched ", \$${_gx_name} (unset)")
        endif()
    endforeach()
    string(APPEND _gx_searched ", $ENV{HOME}/vcpkg, /opt/vcpkg")
    message(FATAL_ERROR
        "GeneralsX: no vcpkg installation found.\n"
        "Searched, in order: ${_gx_searched} "
        "-- each for both .vcpkg-root and scripts/buildsystems/vcpkg.cmake.\n"
        "Fix it with:\n"
        "    git clone https://github.com/microsoft/vcpkg ~/vcpkg\n"
        "    ~/vcpkg/bootstrap-vcpkg.sh -disableMetrics\n"
        "    export VCPKG_ROOT=$ENV{HOME}/vcpkg\n"
        "A FULL (non-shallow) clone is required: vcpkg.json pins a "
        "builtin-baseline commit, and a shallow clone may not contain it.")
endif()

# CMake processes a toolchain file more than once per configure (compiler
# detection re-reads it), so announce the resolved path only on the first pass.
if(NOT GX_VCPKG_ANNOUNCED)
    set(GX_VCPKG_ANNOUNCED ON)
    message(STATUS "GeneralsX: using vcpkg at ${GX_VCPKG_ROOT}")
endif()

# vcpkg's tool binary and its port builds are separate processes that do not
# see the CMake variable above; they read the environment. Exporting it here
# means a configure that found vcpkg through a -D flag or through
# $ENV{HOME}/vcpkg still works for every port vcpkg subsequently builds.
set(ENV{VCPKG_ROOT} "${GX_VCPKG_ROOT}")

include("${GX_VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")
