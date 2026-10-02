#!/usr/bin/env bash
#
# Resolve the Android NDK once, for every script in this directory.
#
# Source it, do not execute it -- it exists to export variables:
#
#     . "$(dirname "$0")/android-ndk-env.sh"
#
# Why: cmake/toolchains/android-ndk-toolchain.cmake already resolves the NDK
# for the CMake configure, but a script that needs the NDK *before* configure
# (to find llvm-readelf, llvm-strip, libc++_shared.so) has no such help, and
# the honest failure mode for "I never exported ANDROID_NDK_HOME" used to be
# a check that told the user to export a variable they may not have needed to
# export at all -- Android Studio installs the NDK under the SDK root whether
# or not any shell variable mentions it.
#
# Sets, on success:
#   ANDROID_NDK_HOME  the resolved NDK directory
#   ANDROID_NDK       the same path (the spelling NDK r19+ prefers, and the one
#                     vcpkg's own port builds read)
#
# Search order, first directory containing build/cmake/android.toolchain.cmake
# wins -- kept in step with cmake/toolchains/android-ndk-toolchain.cmake:
#   1. $ANDROID_NDK_HOME        (already exported; leave it alone)
#   2. $ANDROID_NDK_ROOT        (the spelling NDK r19+ prefers)
#   3. $ANDROID_NDK
#   4. newest NDK under $ANDROID_SDK_ROOT/ndk, then $ANDROID_HOME/ndk
#   5. the pinned NDK the sandboxed and CI builds install
#      (/opt/android-sdk/ndk/27.2.12479018), so those two keep working even if
#      the SDK root variables are not set

# Set to 1 by the caller when a missing NDK should be fatal. The build scripts
# all want that; anything that can proceed without an NDK can leave it 0 and
# check ${ANDROID_NDK_HOME:-} itself.
GX_REQUIRE_ANDROID_NDK="${GX_REQUIRE_ANDROID_NDK:-1}"

_gx_ndk_has_toolchain() {
    [ -n "${1:-}" ] && [ -f "${1}/build/cmake/android.toolchain.cmake" ]
}

GX_ANDROID_NDK=""
for _gx_candidate in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK:-}"; do
    if _gx_ndk_has_toolchain "${_gx_candidate}"; then
        GX_ANDROID_NDK="${_gx_candidate}"
        break
    fi
done

if [ -z "${GX_ANDROID_NDK}" ]; then
    for _gx_sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "/opt/android-sdk"; do
        [ -d "${_gx_sdk}/ndk" ] || continue
        # Highest major version wins, compared as a NUMBER.
        #
        # Not `sort -Vr`: version sort ranks "100.0.0" above "28.0.13004108",
        # and it gets the relative order of plain integers wrong for the same
        # reason CMake's NATURAL comparison does. It looks right for the real
        # NDK majors today (26 < 27 < 28), which is exactly what makes it a
        # trap -- kept numeric here so it stays right.
        _gx_best_major=-1
        for _gx_candidate in "${_gx_sdk}"/ndk/*; do
            _gx_ndk_has_toolchain "${_gx_candidate}" || continue
            _gx_name="${_gx_candidate##*/}"
            case "${_gx_name}" in
                [0-9]*) _gx_major="${_gx_name%%[!0-9]*}" ;;
                # Not version-shaped (a "beta" directory, say): a candidate,
                # but ranked below every numbered NDK.
                *) _gx_major=0 ;;
            esac
            if [ "${_gx_major}" -gt "${_gx_best_major}" ]; then
                _gx_best_major="${_gx_major}"
                GX_ANDROID_NDK="${_gx_candidate}"
            fi
        done
        # Written as a two-line `if` rather than `[ ... ] && break`: that short
        # form is the last command in the loop body, so on the final iteration
        # with nothing found it evaluates false and becomes the loop's exit
        # status -- which `set -e` in every caller of this file treats as a
        # failure, aborting before the error message below is ever printed.
        if [ -n "${GX_ANDROID_NDK}" ]; then
            break
        fi
    done
fi

if [ -z "${GX_ANDROID_NDK}" ] && _gx_ndk_has_toolchain "/opt/android-sdk/ndk/27.2.12479018"; then
    GX_ANDROID_NDK="/opt/android-sdk/ndk/27.2.12479018"
fi

if [ -n "${GX_ANDROID_NDK}" ]; then
    # All three spellings, one directory: CMake's android.toolchain.cmake reads
    # ANDROID_NDK/ANDROID_NDK_ROOT, vcpkg's port builds have been reported to
    # need ANDROID_NDK_ROOT (microsoft/vcpkg#29947), and this project's
    # arm64-android triplet documents ANDROID_NDK_HOME. See the matching note
    # in cmake/toolchains/android-ndk-toolchain.cmake.
    export ANDROID_NDK_HOME="${GX_ANDROID_NDK}"
    export ANDROID_NDK="${GX_ANDROID_NDK}"
    export ANDROID_NDK_ROOT="${GX_ANDROID_NDK}"
else
    GX_NDK_SEARCHED="\$ANDROID_NDK_HOME, \$ANDROID_NDK_ROOT, \$ANDROID_NDK, \$ANDROID_SDK_ROOT/ndk/*, \$ANDROID_HOME/ndk/*, /opt/android-sdk/ndk/27.2.12479018"
    if [ "${GX_REQUIRE_ANDROID_NDK}" = "1" ]; then
        echo "ERROR: no Android NDK found. Searched: ${GX_NDK_SEARCHED}" >&2
        echo "       (each for build/cmake/android.toolchain.cmake)" >&2
        echo "       Install one and point the build at it:" >&2
        echo "         sdkmanager \"ndk;27.2.12479018\"" >&2
        echo "         export ANDROID_NDK_HOME=\$ANDROID_SDK_ROOT/ndk/27.2.12479018" >&2
        echo "       Android Studio's SDK Manager installs the NDK under" >&2
        echo "       ~/Android/Sdk/ndk/<version>, which is found automatically." >&2
        return 1 2>/dev/null || exit 1
    fi
fi

unset _gx_candidate _gx_sdk _gx_name _gx_major _gx_best_major
