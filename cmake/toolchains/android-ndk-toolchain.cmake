# GeneralsX @build Android port 02/10/2026 Android NDK toolchain resolution
# CMake toolchain file that locates an Android NDK and hands over to the NDK's
# own android.toolchain.cmake.
#
# Why this indirection exists: the android-vulkan preset spells it
#
#     VCPKG_CHAINLOAD_TOOLCHAIN_FILE:
#         "$env{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake"
#
# vcpkg includes that path unconditionally ("include(${VCPKG_CHAINLOAD_
# TOOLCHAIN_FILE})", vcpkg.cmake), so an unexported ANDROID_NDK_HOME does not
# skip the include -- it turns the path into "/build/cmake/android.toolchain
# .cmake" and the configure dies with a bare
#
#     CMake Error: Could not find load file: /build/cmake/android.toolchain.cmake
#
# A leading slash, a path nobody typed, no mention of the NDK. That is the
# "no toolchain" failure this file exists to turn into a sentence a human can
# act on.
#
# Search order (first directory containing build/cmake/android.toolchain.cmake
# wins):
#   1. ANDROID_NDK, as a CMake variable
#   2. $ENV{ANDROID_NDK}
#   3. $ENV{ANDROID_NDK_HOME}      -- what docs/port/ANDROID_PORT.md tells you
#   4. $ENV{ANDROID_NDK_ROOT}      -- the spelling NDK r19+ prefers
#   5. $ENV{ANDROID_SDK_ROOT}/ndk/*, then $ENV{ANDROID_HOME}/ndk/*, newest
#      major version first, so a machine with several NDKs installed (Android
#      Studio keeps them all) configures without anyone exporting anything
#
# Once found, the NDK is exported to the environment under all three spellings
# readers use (see the note above the set(ENV{...}) calls below): vcpkg builds
# every dependency (ffmpeg, curl, openssl, freetype, ...) in a separate CMake run
# that reads the NDK from the environment, not from this file.

set(GX_ANDROID_NDK "")

# Directories named by an explicit variable: keep the order above.
foreach(_gx_var ANDROID_NDK ANDROID_NDK_HOME ANDROID_NDK_ROOT)
    if(GX_ANDROID_NDK)
        break()
    endif()
    if(_gx_var AND EXISTS "${${_gx_var}}/build/cmake/android.toolchain.cmake")
        set(GX_ANDROID_NDK "${${_gx_var}}")
    endif()
endforeach()

foreach(_gx_var ANDROID_NDK ANDROID_NDK_HOME ANDROID_NDK_ROOT)
    if(GX_ANDROID_NDK)
        break()
    endif()
    if(DEFINED ENV{${_gx_var}} AND EXISTS "$ENV{${_gx_var}}/build/cmake/android.toolchain.cmake")
        set(GX_ANDROID_NDK "$ENV{${_gx_var}}")
    endif()
endforeach()

# Nothing exported: fall back to whatever is installed under the SDK root.
if(NOT GX_ANDROID_NDK)
    foreach(_gx_sdk "$ENV{ANDROID_SDK_ROOT}" "$ENV{ANDROID_HOME}")
        if(GX_ANDROID_NDK OR NOT _gx_sdk OR NOT IS_DIRECTORY "${_gx_sdk}/ndk")
            continue()
        endif()
        # Highest major version wins, compared as a NUMBER.
        #
        # list(SORT ... COMPARE NATURAL ORDER DESCENDING) is the obvious way to
        # write this and it is wrong: CMake's natural comparison ranks
        # "9.0.0" above "28.0.13004108" and above "100.0.0", because it compares
        # runs of digits as text in a way that a version number never quite
        # matches. It happens to order the real NDK majors correctly today
        # (26 < 27 < 28), so the bug would stay invisible until an NDK major
        # that exposes it. A plain integer comparison is both correct and
        # obvious.
        file(GLOB _gx_ndk_dirs LIST_DIRECTORIES true "${_gx_sdk}/ndk/*")
        set(_gx_best_dir "")
        set(_gx_best_major -1)
        foreach(_gx_dir IN LISTS _gx_ndk_dirs)
            if(NOT EXISTS "${_gx_dir}/build/cmake/android.toolchain.cmake")
                continue()
            endif()
            get_filename_component(_gx_name "${_gx_dir}" NAME)
            if(_gx_name MATCHES "^([0-9]+)")
                set(_gx_major "${CMAKE_MATCH_1}")
            else()
                # Not version-shaped (a "beta" or "canary" directory, say):
                # keep it as a candidate, but ranked below every numbered NDK.
                set(_gx_major 0)
            endif()
            # Strictly greater, so the first entry wins a tie and the iteration
            # order of file(GLOB) -- which is not sorted -- cannot decide it.
            if(_gx_major GREATER _gx_best_major)
                set(_gx_best_major "${_gx_major}")
                set(_gx_best_dir "${_gx_dir}")
            endif()
        endforeach()
        if(_gx_best_dir)
            set(GX_ANDROID_NDK "${_gx_best_dir}")
        endif()
    endforeach()
endif()

if(NOT GX_ANDROID_NDK)
    # Values only where set -- see the note in vcpkg-toolchain.cmake about bare
    # commas reading like a formatting bug.
    set(_gx_searched "ANDROID_NDK (CMake variable)")
    foreach(_gx_name ANDROID_NDK ANDROID_NDK_HOME ANDROID_NDK_ROOT)
        if(DEFINED ENV{${_gx_name}} AND NOT "$ENV{${_gx_name}}" STREQUAL "")
            string(APPEND _gx_searched ", \$${_gx_name}=$ENV{${_gx_name}}")
        else()
            string(APPEND _gx_searched ", \$${_gx_name} (unset)")
        endif()
    endforeach()
    foreach(_gx_name ANDROID_SDK_ROOT ANDROID_HOME)
        if(DEFINED ENV{${_gx_name}} AND NOT "$ENV{${_gx_name}}" STREQUAL "")
            string(APPEND _gx_searched ", \$${_gx_name}/ndk/*")
        else()
            string(APPEND _gx_searched ", \$${_gx_name}/ndk/* (unset)")
        endif()
    endforeach()
    message(FATAL_ERROR
        "GeneralsX: no Android NDK found.\n"
        "Searched, in order: ${_gx_searched} "
        "-- each for build/cmake/android.toolchain.cmake.\n"
        "Install one (NDK r26 or newer) and point the build at it:\n"
        "    sdkmanager \"ndk;27.2.12479018\"\n"
        "    export ANDROID_NDK_HOME=\$ANDROID_SDK_ROOT/ndk/27.2.12479018\n"
        "Android Studio's SDK Manager installs the same NDK under "
        "~/Android/Sdk/ndk/<version>, which the search above picks up on its own.")
endif()

# Announced once: see the note in vcpkg-toolchain.cmake -- a toolchain file is
# read again during compiler detection.
if(NOT GX_ANDROID_NDK_ANNOUNCED)
    set(GX_ANDROID_NDK_ANNOUNCED ON)
    message(STATUS "GeneralsX: using Android NDK at ${GX_ANDROID_NDK}")
endif()

# Same reasoning as the vcpkg resolver: the NDK is needed by vcpkg's own port
# builds, which are separate processes that only see the environment.
#
# All three spellings are exported, not just one, because different readers
# look for different ones: CMake's own android.toolchain.cmake accepts
# ANDROID_NDK_ROOT or ANDROID_NDK; vcpkg's port builds have historically been
# reported to need ANDROID_NDK_ROOT (microsoft/vcpkg#29947, #27564); and this
# project's arm64-android triplet documents ANDROID_NDK_HOME. They all name the
# same resolved directory, so exporting the set costs nothing and removes the
# question of which one a given dependency happens to read.
set(ENV{ANDROID_NDK} "${GX_ANDROID_NDK}")
set(ENV{ANDROID_NDK_ROOT} "${GX_ANDROID_NDK}")
set(ENV{ANDROID_NDK_HOME} "${GX_ANDROID_NDK}")

include("${GX_ANDROID_NDK}/build/cmake/android.toolchain.cmake")
