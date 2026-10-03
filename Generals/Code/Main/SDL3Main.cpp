/*
**	Command & Conquer Generals(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
** SDL3Main.cpp
**
** Entry point for Linux builds using SDL3 windowing and DXVK graphics.
**
** TheSuperHackers @feature CnC_Generals_Linux 07/02/2026
** Entry point replaces WinMain() for Linux builds.
** Instantiates SDL3GameEngine and calls GameMain().
*/

#ifndef _WIN32

// SYSTEM INCLUDES
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <cstdlib>
#include <cctype>
#include <cstring>
#include <cstdio>
#include <unistd.h>   // _exit()
#include <glob.h>     // glob() for Vulkan ICD discovery

// GeneralsX @build Android port Core/Generals 02/10/2026 On Android this
// include renames main() to SDL_main, which the SDLActivity Java shell
// (android-generals/) invokes inside the app process after loading libmain.so.
// Mirrors the SAGE_MOBILE_PLATFORM include in GeneralsMD/Code/Main/SDL3Main.cpp.
#if defined(__ANDROID__)
#include <SDL3/SDL_main.h>
#include <cerrno>
// GeneralsX @bugfix Android port Core/Generals 03/10/2026 The Vulkan/GLES
// decision has to be made in TWO places that must agree: here (which kind of
// SDL window/surface to create) and DX8Wrapper::Init() in Core's
// WW3D2/dx8wrapper.cpp (which D3D8 implementation to load). The Zero Hour
// entry point already routes both through d3d8gles_ShouldUseVulkanBackend();
// this file did not, so it ALWAYS created a Vulkan window while
// dx8wrapper.cpp -- whose default is the native GLES backend -- loaded
// Direct3DCreate8_GLES underneath it. SDL_GL_CreateContext() then failed with
// "the specified window isn't an OpenGL window" inside
// WebGLPipeline::initContext(), whose false return the caller ignores, so
// D3D device creation still "succeeded", the engine ran normally (audio,
// game logic), and nothing was ever presented: a black screen. This is the
// exact failure described in d3d8gles.h's comment on this function.
// GeneralsX @build Android port Core/Generals 03/10/2026 d3d8gles.h is
// reachable because g_generals links the d3d8gles target on Android, which
// exports this include directory PUBLIC (see d3d8gles/CMakeLists.txt).
#include "d3d8gles.h"
#endif

/**
 * UseVulkanBackend / UseANGLE
 *
 * GeneralsX @bugfix Android port Core/Generals 03/10/2026 Thin adapters over
 * the shared single source of truth (d3d8gles.cpp), mirroring the identical
 * wrappers in GeneralsMD/Code/Main/SDL3Main.cpp. Non-Android builds are always
 * the Vulkan/DXVK path, exactly as before this change.
 */
static bool UseVulkanBackend()
{
#if defined(__ANDROID__)
	return d3d8gles_ShouldUseVulkanBackend();
#else
	return true;
#endif
}

static bool UseANGLE()
{
#if defined(__ANDROID__)
	return d3d8gles_ShouldUseANGLE();
#else
	return false;
#endif
}

// USER INCLUDES (match WinMain.cpp pattern)
#include "Lib/BaseType.h"
#include "Common/CommandLine.h"
#include "Common/CriticalSection.h"
#include "Common/GlobalData.h"
#include "Common/GameEngine.h"
#include "Common/GameMemory.h"
#include "Common/Debug.h"
#include "Common/version.h"  // GeneralsX @bugfix BenderAI 14/02/2026 Version class + TheVersion extern
#include "SDL3GameEngine.h"

// DXVK WSI
#define DXVK_WSI_SDL3 1
#include <wsi/native_wsi.h>

// CRITICAL SECTIONS (Linux needs these too)
static CriticalSection critSec1;
static CriticalSection critSec2;
static CriticalSection critSec3;
static CriticalSection critSec4;
static CriticalSection critSec5;

// GLOBAL COMMAND LINE ARGUMENTS
// TheSuperHackers @build felipebraz 13/02/2026
// Store argc/argv from main() for use by CommandLine.cpp parseCommandLine() on Linux
// Windows provides these automatically; Linux needs explicit globals
int __argc = 0;          ///< global argument count
char** __argv = nullptr; ///< global argument vector

// GLOBAL WINDOW HANDLE
// TheSuperHackers @build felipebraz 13/02/2026
// ApplicationHWnd is declared extern in Generals/Code/Main/WinMain.h
// On Linux, we cast SDL_Window* to HWND type for compatibility
HWND ApplicationHWnd = nullptr;  ///< our application window handle

// GLOBAL SDL3 WINDOW
// GeneralsX @feature felipebraz 16/02/2026
// SDL3 window created in main() before GameMain(), stored globally for engine access
SDL_Window* TheSDL3Window = nullptr;

// GAME TEXT FILE PATHS
// TheSuperHackers @build felipebraz 13/02/2026
// GameText.cpp uses these paths to load CSF and STR files (game localization)
// Format %s is replaced with language code in GameTextManager::init()
// GeneralsX @bugfix BenderAI 13/02/2026 - Fix case-sensitivity on Linux (generals.csf vs Generals.csf)
const Char *g_csfFile = "data/%s/generals.csf";  ///< CSF file path (lowercase for Linux compatibility)
const Char *g_strFile = "data/Generals.str";     ///< STR file path

// Extern declarations (from GameMain.cpp)
extern Int GameMain();

/**
 * FilterSoftwareVulkanICDs
 *
 * Sets VK_DRIVER_FILES to only hardware Vulkan ICDs, excluding LLVMpipe/lavapipe.
 *
 * Workaround for Mesa/LLVM 20.x bug: libvulkan_lvp.so (LLVMpipe Vulkan ICD) crashes
 * during dlopen() static initialization with a null-ptr deref in llvm::Regex::Regex().
 * The Vulkan loader loads ALL ICDs found in the ICD directories when
 * vkEnumerateInstanceExtensionProperties() is called, which triggers the crash.
 * Filtering hardware-only ICDs via VK_DRIVER_FILES prevents loading libvulkan_lvp.so.
 *
 * Only applied when neither VK_DRIVER_FILES nor VK_ICD_FILENAMES is already set,
 * so the user can always override by setting those variables externally.
 *
 * GeneralsX @bugfix BenderAI 06/03/2026
 */
static void FilterSoftwareVulkanICDs()
{
	if (getenv("VK_DRIVER_FILES") || getenv("VK_ICD_FILENAMES")) {
		return;
	}

	auto icd_is_software = [](const char *name) -> bool {
		char low[256] = "";
		for (int i = 0; name[i] && i < 255; ++i) {
			low[i] = (char)tolower((unsigned char)name[i]);
		}
		return strstr(low, "lvp") || strstr(low, "lavapipe") || strstr(low, "softpipe") || strstr(low, "llvmpipe");
	};

	static char hw_icds[4096] = "";
	const char *patterns[] = {
		"/usr/share/vulkan/icd.d/*.json",
		"/etc/vulkan/icd.d/*.json",
		nullptr
	};

	glob_t gl = {};
	int gflags = 0;
	for (int i = 0; patterns[i]; ++i) {
		if (glob(patterns[i], gflags, nullptr, &gl) == 0) {
			gflags = GLOB_APPEND;
		}
	}

	bool found_hw = false;
	for (size_t i = 0; i < gl.gl_pathc; ++i) {
		const char *path = gl.gl_pathv[i];
		const char *base = strrchr(path, '/');
		base = base ? base + 1 : path;
		if (icd_is_software(base)) {
			fprintf(stderr, "INFO: Vulkan ICD filter: skipping software ICD '%s'\n", base);
			continue;
		}
		if (found_hw) {
			strncat(hw_icds, ":", sizeof(hw_icds) - strlen(hw_icds) - 1);
		}
		strncat(hw_icds, path, sizeof(hw_icds) - strlen(hw_icds) - 1);
		found_hw = true;
	}
	globfree(&gl);

	if (found_hw) {
		setenv("VK_DRIVER_FILES", hw_icds, 1);
		fprintf(stderr, "INFO: Vulkan ICD filter: VK_DRIVER_FILES=%s\n", hw_icds);
	} else {
		fprintf(stderr, "WARNING: Vulkan ICD filter: no hardware ICDs found, LLVMpipe exclusion skipped\n");
		fprintf(stderr, "WARNING: If startup crashes in libvulkan_lvp.so, set VK_DRIVER_FILES manually\n");
	}
}

/**
 * FilterPipeWireOpenAL
 *
 * Sets ALSOFT_DRIVERS to skip PipeWire, falling back to pulse/alsa.
 *
 * Workaround for openal-soft PipeWire backend crash: alcOpenDevice() segfaults
 * inside the PipeWire backend while opening the default playback device.
 * The crash occurs in PipeWire's stream/context internals and is unrecoverable
 * from userspace. Excluding PipeWire via ALSOFT_DRIVERS causes openal-soft to
 * fall back to the PulseAudio backend, which works correctly on PipeWire systems
 * via the PulseAudio compatibility layer.
 *
 * NOTE: openal-soft reads ALSOFT_DRIVERS from a static global constructor when
 * libopenal.so is loaded by the dynamic linker, which is before main() runs.
 * This function is therefore only effective for builds that use lazy
 * initialization. The authoritative fix is in the launch scripts (run-linux-zh.sh
 * etc.), which set ALSOFT_DRIVERS before the binary starts.
 *
 * Only applied when ALSOFT_DRIVERS is not already set by the user.
 *
 * GeneralsX @bugfix 09/03/2026
 */
static void FilterPipeWireOpenAL()
{
	// GeneralsX @bugfix Copilot 24/03/2026 PipeWire/OpenAL workaround is Linux-only; keep macOS CoreAudio backend selection untouched.
	// GeneralsX @bugfix Android port Core/Generals 02/10/2026 Android is also
	// "linux" to the preprocessor, and forcing the host backend list there
	// (pulse/alsa/oss/...) would skip openal-soft's Android backends entirely
	// (aaudio/opensles) and land on "null" -- silent audio. Keep the host
	// backends for real desktop Linux only.
	#if defined(__linux__) && !defined(__ANDROID__)
	// Crash: alcOpenDevice() hits 'movaps %xmm1,0x26260(%rbx)' — SSE movaps requires
	// 16-byte alignment; a misaligned ALCdevice struct faults regardless of backend.
	// Disabling CPU extensions forces openal-soft to use scalar code that has no
	// alignment requirements. Also exclude pipewire which has its own crash at
	// device-open time on PipeWire 1.4.x.
	// NOTE: these env vars are authoritative only when set before the binary loads
	// (openal-soft reads them from a static constructor). The launch scripts set them
	// first; this is a best-effort fallback for lazy-init builds.
	if (!getenv("ALSOFT_DISABLE_CPU_EXTS")) {
		setenv("ALSOFT_DISABLE_CPU_EXTS", "all", 1);
		fprintf(stderr, "INFO: OpenAL: ALSOFT_DISABLE_CPU_EXTS=all (movaps alignment crash workaround)\n");
	}
	if (!getenv("ALSOFT_DRIVERS")) {
		setenv("ALSOFT_DRIVERS", "pulse,alsa,oss,jack,null,wave", 1);
		fprintf(stderr, "INFO: OpenAL: ALSOFT_DRIVERS=pulse,alsa,oss,jack,null,wave (pipewire excluded)\n");
	}
	#else
	fprintf(stderr, "INFO: OpenAL: keeping default driver selection on non-Linux platform\n");
	#endif
}

/**
 * CreateGameEngine
 *
 * Factory function for SDL3GameEngine on Linux.
 * Called by GameMain() to instantiate platform-specific engine.
 *
 * @return SDL3GameEngine instance
 */
GameEngine *CreateGameEngine(void)
{
	fprintf(stderr, "INFO: CreateGameEngine() - Creating SDL3GameEngine for Linux\n");
	SDL3GameEngine *engine = NEW SDL3GameEngine();
	return engine;
}

#if defined(__ANDROID__)
/**
 * ApplyAndroidWorkingDirectory
 *
 * Enter the game folder the user picked in the Generals launcher
 * (GeneralsLauncherActivity) BEFORE any engine code touches the filesystem:
 * the engine loads every .big archive in its working directory, so this
 * chdir() is what points the game at the user's own legally-obtained files.
 * The launcher passes the folder on the command line as -gxGameDir <path>
 * (see GeneralsGameActivity.getArguments() in android-generals/); the path is carried by argv
 * instead of the gamedata_path.txt marker + JNI lookup the Zero Hour shell
 * uses (GeneralsMD/Code/Main/SDL3Main.cpp) because a plain argv entry needs
 * no JNI plumbing here and reaches us before SDL's own bootstrap finishes.
 *
 * Also mirrors stderr into <gamedir>/generals-stderr.log: Android sends
 * native stderr to /dev/null (only SDL_Log reaches logcat), and an engine
 * this chatty is undebuggable blind. The previous session's log is kept as
 * *-prev.log -- a session that ends in a low-memory kill leaves no crash
 * report, so the prior log is often the only evidence.
 *
 * @return true when a working directory was entered
 */
static bool ApplyAndroidWorkingDirectory(int argc, char *argv[])
{
	const char *gameDir = nullptr;
	for (int i = 1; i + 1 < argc; ++i) {
		if (strcmp(argv[i], "-gxGameDir") == 0 && argv[i + 1] != nullptr && argv[i + 1][0] != '\0') {
			gameDir = argv[i + 1];
			break;
		}
	}
	if (gameDir == nullptr) {
		fprintf(stderr, "WARNING: no -gxGameDir argument; staying in the process CWD (the engine will not find game data)\n");
		return false;
	}
	if (chdir(gameDir) != 0) {
		fprintf(stderr, "WARNING: chdir('%s') failed: %s\n", gameDir, strerror(errno));
		return false;
	}
	fprintf(stderr, "INFO: Android working directory: %s\n", gameDir);

	char logPath[1024], prevPath[1024];
	snprintf(logPath, sizeof(logPath), "%s/generals-stderr.log", gameDir);
	snprintf(prevPath, sizeof(prevPath), "%s/generals-stderr-prev.log", gameDir);
	rename(logPath, prevPath);
	if (freopen(logPath, "w", stderr) != nullptr) {
		setvbuf(stderr, nullptr, _IOLBF, 0);  // line-buffered: a crash still flushes recent lines
	}
	return true;
}
#endif // __ANDROID__

/**
 * main
 *
 * Linux entry point (replaces WinMain on Windows).
 * Initializes subsystems and calls GameMain().
 *
 * @param argc Command line argument count
 * @param argv Command line arguments
 * @return Exit code (0 = success)
 */
int main(int argc, char* argv[])
{
	int exitcode = 1;

	// TheSuperHackers @build felipebraz 13/02/2026
	// Store command line arguments in globals for CommandLine.cpp parser
	__argc = argc;
	__argv = argv;

#if defined(__ANDROID__)
	// GeneralsX @feature Android port Core/Generals 02/10/2026 chdir to the
	// launcher-selected game folder and start the stderr log BEFORE the banner
	// below, so the banner itself lands in the log file.
	ApplyAndroidWorkingDirectory(argc, argv);
#endif

	fprintf(stderr, "=================================================\n");
	fprintf(stderr, " Command & Conquer Generals (Linux)\n");
	fprintf(stderr, " SDL3 + DXVK Build\n");
	fprintf(stderr, "=================================================\n\n");

	try {
		// Initialize critical sections (required by game engine)
		TheAsciiStringCriticalSection = &critSec1;
		TheUnicodeStringCriticalSection = &critSec2;
		TheDmaCriticalSection = &critSec3;
		TheMemoryPoolCriticalSection = &critSec4;
		TheDebugLogCriticalSection = &critSec5;

		// Initialize memory manager early (required by NEW operator)
		initMemoryManager();

		// GeneralsX @bugfix BenderAI 14/02/2026 Initialize Version singleton
		// GameEngine::init() calls updateWindowTitle() which uses TheVersion
		// Must be created before GameMain() to avoid nullptr dereference
		TheVersion = NEW Version;

		// Parse command line (CommandLine class handles argc/argv internally)
		// TheSuperHackers @build felipebraz 10/02/2026 Phase 1.5
		// Store argc/argv for CommandLine parser to access via _NSGetArgc/_NSGetArgv or /proc/self/cmdline
		// For now, let CommandLine::parseCommandLineForStartup() handle this
		CommandLine::parseCommandLineForStartup();

		// GeneralsX @bugfix Copilot 17/05/2026 Skip SDL3 window bootstrap for CLI/headless replay execution.
		const bool isHeadlessMode = (TheGlobalData != nullptr && TheGlobalData->m_headless);
		if (isHeadlessMode) {
			fprintf(stderr, "INFO: Headless mode detected, skipping SDL3 video/Vulkan window initialization\n");
		} else {

			// GeneralsX @bugfix felipebraz 16/02/2026
			// Initialize SDL3 and Vulkan BEFORE creating GameEngine (fighter19 pattern)
			// This prevents LLVM SIGSEGV crash during Vulkan driver enumeration
			// Must be done here, not in SDL3GameEngine::init() which is too late
			fprintf(stderr, "INFO: Initializing SDL3 video subsystem...\n");
#if defined(__ANDROID__)
			// GeneralsX @feature Android port Core/Generals 02/10/2026 (mirrors
			// GeneralsMD/Code/Main/SDL3Main.cpp): don't let SDL block its main
			// thread on pause -- the engine owns its own frame pacing, and a
			// blocked main thread on resume looks like a hang.
			SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
#endif
			// GeneralsX @bugfix Android port Core/Generals 03/10/2026 Decide the
			// backend ONCE, before SDL brings up the video driver, and use the
			// same decision for the SDL window/surface and (via the shared
			// d3d8gles helper) for DX8Wrapper::Init(). SDL loads EGL while
			// initializing the video subsystem rather than lazily at
			// context-creation time, so the ANGLE EGL_LIBRARY hint below has
			// to be set before SDL_InitSubSystem(). See d3d8gles.h for why the
			// two copies of this decision drifted apart into a black screen.
			const bool useVulkan = UseVulkanBackend();
#if defined(__ANDROID__)
			if (!useVulkan && UseANGLE()) {
				SDL_SetHint(SDL_HINT_EGL_LIBRARY, "libEGL_angle.so");

				// GeneralsX @bugfix Android port Core/Generals 03/10/2026 Mirror
				// of the Zero Hour entry point's ANGLE fix (08/30/2026): ANGLE's
				// enablePreRotateSurfaces pre-rotates its own rendering based on
				// the surface's real currentTransform, and a whole-frame
				// vertical flip was verified on a Redmi Note 8 Pro through
				// exactly that path while the same device's DXVK/Vulkan path
				// (which hardcodes preTransform = IDENTITY and lets
				// SurfaceFlinger do the rotation blit) was correct. Disable it so
				// ANGLE falls back to the already-proven compositor-blit
				// behavior.
				setenv("ANGLE_FEATURE_OVERRIDES_DISABLED", "enablePreRotateSurfaces", 1);
			}
#endif
			if (!SDL_InitSubSystem(SDL_INIT_VIDEO | SDL_INIT_AUDIO)) {
				fprintf(stderr, "FATAL: Failed to initialize SDL3: %s\n", SDL_GetError());
				return 1;
			}

			if (useVulkan) {
				// Set DXVK WSI driver before loading Vulkan
				setenv("DXVK_WSI_DRIVER", "SDL3", 1);

				// GeneralsX @bugfix BenderAI 06/03/2026 - Exclude LLVMpipe Vulkan ICD before loading Vulkan.
				// libvulkan_lvp.so crashes during static initialization with LLVM 20.x when the Vulkan
				// loader enumerates all ICDs. Restrict to hardware ICDs first.
				// GeneralsX @bugfix Android port Core/Generals 02/10/2026 Host ICD
				// directories (/usr/share, /etc) do not exist on Android -- the
				// device's libvulkan.so IS the driver there -- so skip the filter (it
				// would only print confusing "no hardware ICDs" warnings).
#if !defined(__ANDROID__)
				FilterSoftwareVulkanICDs();
#endif
			}
			FilterPipeWireOpenAL();

			if (useVulkan) {
				// Load Vulkan library for DXVK DirectX8→Vulkan translation
				fprintf(stderr, "INFO: Loading Vulkan library...\n");
				if (!SDL_Vulkan_LoadLibrary(nullptr)) {
					fprintf(stderr, "WARNING: Failed to load Vulkan: %s\n", SDL_GetError());
					fprintf(stderr, "WARNING: Continuing without Vulkan (may use software rendering)\n");
				}
			} else {
				// GeneralsX @bugfix Android port Core/Generals 03/10/2026 GLES3
				// context attributes must be set BEFORE SDL_CreateWindow -- SDL
				// only applies them to windows created after the call. The
				// context itself is created later, by DX8Wrapper::Init() ->
				// the d3d8gles backend (WebGLPipeline::initContext).
				fprintf(stderr, "INFO: Using native GLES3 backend (no Vulkan)\n");
				SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
				SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
				SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
				SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
				SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
				SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
				// GeneralsX @bugfix Android port Core/Generals 03/10/2026
				// ALPHA_SIZE 8 mirrors GeneralsMD/Code/Main/SDL3Main.cpp: an
				// opaque (alpha 0) EGL config is a rarely-exercised path on
				// Android, and Vulkan/DXVK declares
				// VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR explicitly while EGL has no
				// equivalent knob -- its blend behavior is inferred from the
				// chosen config's alpha bits. See that file's comment.
				SDL_GL_SetAttribute(SDL_GL_ALPHA_SIZE, 8);
				SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
				SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
				SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
			}

			// Create SDL3 window matching the chosen backend. A Vulkan window
			// cannot host a GL context and vice versa, so this flag and the one
			// DX8Wrapper::Init() picks must never disagree.
			fprintf(stderr, "INFO: Creating SDL3 %s window...\n", useVulkan ? "Vulkan" : "OpenGL ES");
			Uint32 windowFlags = (useVulkan ? SDL_WINDOW_VULKAN : SDL_WINDOW_OPENGL) | SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIDDEN;  // Start hidden, show after D3D init
#if defined(__ANDROID__)
			// GeneralsX @feature Android port Core/Generals 02/10/2026 (mirrors
			// GeneralsMD/Code/Main/SDL3Main.cpp):
			// HIGH_PIXEL_DENSITY -- request a native-resolution drawable; without
			// it the swapchain renders at point size and the display upscales,
			// visibly blurring textures and terrain.
			// FULLSCREEN -- immersive mode: hides the status/navigation bars so
			// the RTS UI owns the whole panel.
			windowFlags |= SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_FULLSCREEN;
#endif
			TheSDL3Window = SDL_CreateWindow(
				"Command & Conquer Generals",
				1024, 768,  // Default resolution
				windowFlags
			);

			if (!TheSDL3Window) {
				fprintf(stderr, "FATAL: Failed to create SDL3 window: %s\n", SDL_GetError());
				SDL_Quit();
				return 1;
			}

			// Store window handle globally (cast SDL_Window* to HWND for compatibility)
			ApplicationHWnd = (HWND)TheSDL3Window;
			fprintf(stderr, "INFO: SDL3 window created successfully\n");

#if defined(__ANDROID__)
			// GeneralsX @feature Android port Core/Generals 02/10/2026 Match the
			// game's internal resolution to the real panel: the engine's 4:3
			// default would otherwise render inside the wide display pillarboxed.
			// Injected as -xres/-yres argv entries so the normal command-line path
			// applies them -- CommandLine::parseCommandLineForEngineInit() (which
			// runs later, from GameEngine::init) re-reads the __argc/__argv
			// globals, so replacing them here lands. A user-passed -xres/-yres
			// still wins: the parser lets later arguments override earlier ones
			// and ours go last, so only add them when the user passed neither.
			{
				bool userSetRes = false;
				for (int i = 1; i < __argc; ++i) {
					if (strcmp(__argv[i], "-xres") == 0 || strcmp(__argv[i], "-yres") == 0) {
						userSetRes = true;
						break;
					}
				}
				// WindowManager can take a handful of frames to apply the
				// manifest's landscape lock to a freshly created Activity; a single
				// snapshot right after SDL_CreateWindow can still catch a stale
				// portrait size, which would bake a wrong -xres/-yres for the whole
				// session. Poll briefly for four consecutive identical landscape
				// readings (200ms stable) before trusting the size, same heuristic
				// as the Zero Hour shell.
				int prevW = -1, prevH = -1;
				int stableCount = 0;
				for (int attempt = 0; attempt < 60; ++attempt) {
					int w = 0, h = 0;
					SDL_GetWindowSizeInPixels(TheSDL3Window, &w, &h);
					if (w > h && w == prevW && h == prevH) {
						if (++stableCount >= 4) break;
					} else {
						stableCount = 0;
					}
					prevW = w;
					prevH = h;
					SDL_PumpEvents();
					SDL_Delay(50);
				}
				// Use the pixel size of the high-density drawable: the game renders
				// 1:1 into the native-resolution swapchain, and fonts/UI rescale via
				// the engine's resolution-aware font scaling (GlobalLanguage).
				int winW = 0, winH = 0;
				SDL_GetWindowSizeInPixels(TheSDL3Window, &winW, &winH);
				if (!userSetRes && winW > 0 && winH > 0 && winW > winH) {
					static char xresVal[16], yresVal[16];
					static char xresFlag[] = "-xres";
					static char yresFlag[] = "-yres";
					int xres = winW & ~1;  // keep it even
					int yres = winH;
					// Prefer a Resolution already saved in the working directory's
					// Options.ini over the window-derived one (same rationale as the
					// Zero Hour shell): the engine-init parse runs after Options.ini
					// has applied its saved value, so re-injecting the window size
					// unconditionally would silently discard the user's preference on
					// every launch. Options.ini is plain "key = value" per line.
					{
						FILE *fp = fopen("Options.ini", "r");
						if (fp != nullptr) {
							char line[256];
							while (fgets(line, sizeof(line), fp)) {
								int savedX = 0, savedY = 0;
								if (sscanf(line, " Resolution = %d %d", &savedX, &savedY) == 2 &&
								    savedX > 0 && savedY > 0) {
									xres = savedX & ~1;
									yres = savedY;
									fprintf(stderr, "INFO: using saved Resolution %dx%d from Options.ini instead of window size %dx%d\n",
									        xres, yres, winW, winH);
									break;
								}
							}
							fclose(fp);
						}
					}

					snprintf(xresVal, sizeof(xresVal), "%d", xres);
					snprintf(yresVal, sizeof(yresVal), "%d", yres);

					static char *newArgv[64];
					int n = 0;
					for (int i = 0; i < __argc && n < 59; ++i) {
						newArgv[n++] = __argv[i];
					}
					newArgv[n++] = xresFlag;
					newArgv[n++] = xresVal;
					newArgv[n++] = yresFlag;
					newArgv[n++] = yresVal;
					newArgv[n] = nullptr;
					__argv = newArgv;
					__argc = n;
					fprintf(stderr, "INFO: Android resolution injected: -xres %s -yres %s (window pixels %dx%d)\n",
					        xresVal, yresVal, winW, winH);
				}
			}
#endif // __ANDROID__
		}

		// Call cross-platform game entry point
		exitcode = GameMain();

		fprintf(stderr, "INFO: GameMain() returned with code %d\n", exitcode);

	} catch (const std::exception& e) {
		fprintf(stderr, "FATAL: Unhandled exception in main(): %s\n", e.what());
		exitcode = 1;
	} catch (...) {
		fprintf(stderr, "FATAL: Unknown exception in main()\n");
		exitcode = 1;
	}

	// Cleanup SDL3 resources
	if (TheSDL3Window) {
		SDL_DestroyWindow(TheSDL3Window);
		TheSDL3Window = nullptr;
		ApplicationHWnd = nullptr;
	}
	SDL_Quit();

	// GeneralsX @bugfix BenderAI 14/02/2026 Cleanup Version singleton
	if (TheVersion) {
		delete TheVersion;
		TheVersion = nullptr;
	}

	// GeneralsX @bugfix BenderAI 19/02/2026 Shutdown memory manager BEFORE nulling critical
	// sections. Without this, global pool destructors (ObjectPoolClass) crash during atexit()
	// because they call ::operator delete after the memory manager is already gone (SIGSEGV).
	// Matches WinMain.cpp cleanup order: TheVersion -> shutdownMemoryManager -> null critSecs.
	shutdownMemoryManager();

	// Cleanup critical sections (after memory manager, which may use them during shutdown)
	TheAsciiStringCriticalSection = nullptr;
	TheUnicodeStringCriticalSection = nullptr;
	TheDmaCriticalSection = nullptr;
	TheMemoryPoolCriticalSection = nullptr;
	TheDebugLogCriticalSection = nullptr;

	fprintf(stderr, "\nExiting with code %d\n", exitcode);

	// GeneralsX @bugfix BenderAI 25/02/2026 — use _exit() to skip C++ global destructors.
	// On macOS, __cxa_finalize_ranges runs ObjectPoolClass<X,256> global dtors after main() returns.
	// Those dtors crash with a corrupted BlockListHead (SIGSEGV at 0x4ade32ec4ade0018) because
	// pool block memory was already reused/overwritten during game shutdown.
	// Windows never had this problem — ExitProcess() terminates without running C++ global dtors.
	// _exit() matches that behavior. Explicit cleanup already done above (SDL_Quit, shutdownMemoryManager).
	_exit(exitcode);
}

#endif // !_WIN32
