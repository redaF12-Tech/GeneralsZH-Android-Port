# z stands for Zero Hour, i stands for Interface
add_library(zi_gameengine_include INTERFACE)
add_library(zi_libraries_source_wwvegas INTERFACE)
add_library(zi_main INTERFACE)
add_library(zi_always INTERFACE)
add_library(zi_always_no_pch INTERFACE) # Use this for Shared Libs with MFC AFX

target_include_directories(zi_gameengine_include INTERFACE "GameEngine/Include")
target_include_directories(zi_libraries_source_wwvegas INTERFACE "Libraries/Source/WWVegas")
target_include_directories(zi_main INTERFACE "Main")
target_include_directories(zi_always INTERFACE "CompatLib/Include")  # TheSuperHackers @build 08/02/2026 Add DirectX 8 compat headers

target_compile_definitions(zi_always INTERFACE
    RTS_ZEROHOUR=1
)
target_compile_definitions(zi_always_no_pch INTERFACE
    RTS_ZEROHOUR=1
)

# GeneralsX @bugfix Generals base game 02/10/2026 The Zero Hour high-framerate
# simulation family, attached to the Zero Hour interface targets rather than
# declared globally with add_compile_definitions() in the top-level
# CMakeLists.txt. See the long comment there for the full history and for why
# the global scope broke the base game (it handed a Zero Hour capability macro
# to the base engine's own copy of the shared Core sources).
#
# It has to be visible to Core as well as to GeneralsMD: Core files
# (ParticleSys.cpp, htree.cpp, FrameRateLimit.cpp, seglinerenderer.cpp, ...) use
# these macros, and GameLogic declares m_frameLegacy/m_frameLegacyLast behind
# them, so any two targets that disagreed would disagree about the class layout.
# Core cannot include a GeneralsMD header (hence not NextGenMP_defines.h), but
# zi_always reaches Core's sources transitively: z_gameengine links both
# zi_always and corei_gameengine_private, the INTERFACE that carries Core's
# source list. Every Zero Hour target links zi_always, so nothing that actually
# is Zero Hour loses the macro.
if(SAGE_HIGH_FPS_SIM)
    set(GX_ZEROHOUR_HIGH_FPS_DEFINITIONS
        GENERALS_ONLINE_HIGH_FPS_SERVER=1
        GENERALS_ONLINE_HIGH_FPS_LIMIT=60
        GENERALS_ONLINE_HIGH_FPS_FRAME_MULTIPLIER=2
    )
else()
    set(GX_ZEROHOUR_HIGH_FPS_DEFINITIONS
        GENERALS_ONLINE_HIGH_FPS_LIMIT=30
        GENERALS_ONLINE_HIGH_FPS_FRAME_MULTIPLIER=1
    )
endif()
# Unlocks the render framerate. Required at 60 Hz, optional but desirable at 30.
list(APPEND GX_ZEROHOUR_HIGH_FPS_DEFINITIONS GENERALS_ONLINE_HIGH_FPS_RENDER=1)

target_compile_definitions(zi_always INTERFACE ${GX_ZEROHOUR_HIGH_FPS_DEFINITIONS})
target_compile_definitions(zi_always_no_pch INTERFACE ${GX_ZEROHOUR_HIGH_FPS_DEFINITIONS})
target_link_libraries(zi_gameengine_include INTERFACE
    corei_gameengine_include
)
target_link_libraries(zi_always INTERFACE
    corei_always # Must stay below so headers from game are included first
)
target_link_libraries(zi_always_no_pch INTERFACE
    corei_always_no_pch # Must stay below so headers from game are included first
)

# CompatLib: Platform compatibility layer (Linux headers, d3d8lib config)
add_subdirectory(CompatLib)

# Contains internal libraries
add_subdirectory(Libraries)

# Base GameEngine library
add_subdirectory(GameEngine)

# Platform specific GameEngine code
add_subdirectory(GameEngineDevice)

# Contains the main game exe target.
add_subdirectory(Main)

if (RTS_BUILD_ZEROHOUR_TOOLS OR RTS_BUILD_ZEROHOUR_EXTRAS)
    add_subdirectory(Tools)
endif()
