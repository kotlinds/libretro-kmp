# libretro-kmp

Kotlin Multiplatform frontend for [libretro](https://www.libretro.com/): load emulator cores (melonDS, mGBA, DeSmuME,
Genesis Plus GX, Snes9x…) and drive them from Kotlin — run frames, receive video and audio, send inputs, read and write
the emulated memory, save and restore states.
Supports Android, iOS, macOS, Linux, Windows, and JVM/Desktop from a single Kotlin API.

[![License](https://img.shields.io/github/license/kotlinds/libretro-kmp)](LICENSE)
[![Maven Central Version](https://img.shields.io/maven-central/v/dev.kotlinds/libretro-kmp)](https://klibs.io/project/kotlinds/libretro-kmp)
[![Issues](https://img.shields.io/github/issues/kotlinds/libretro-kmp)]()
[![Pull Requests](https://img.shields.io/github/issues-pr/kotlinds/libretro-kmp)]()

## What's included

| Component                   | Version                    |
|-----------------------------|----------------------------|
| libretro API (`libretro.h`) | 1 (libretro-common master) |
| JNA (JVM/Desktop binding)   | 5.15.0                     |
| Kotlin                      | 2.4.20                     |

### Supported targets

| Platform                                 | Integration                     |
|------------------------------------------|---------------------------------|
| Android (arm64-v8a, armeabi-v7a, x86_64) | JNI bridge (bundled) + `dlopen` |
| iOS device (arm64)                       | cinterop + `dlopen`             |
| iOS simulator (arm64)                    | cinterop + `dlopen`             |
| macOS (arm64)                            | cinterop + `dlopen`             |
| Linux (x64, arm64)                       | cinterop + `dlopen`             |
| Windows (x64)                            | cinterop + `LoadLibrary`        |
| JVM/Desktop                              | JNA                             |

libretro cores are not bundled: they are shared libraries (`.dylib`, `.so`, `.dll`) that you load from a path at
runtime. Prebuilt cores for every platform are available on the
[libretro buildbot](https://buildbot.libretro.com/nightly/), e.g.
`https://buildbot.libretro.com/nightly/apple/osx/arm64/latest/melonds_libretro.dylib.zip`.

## Installation

Add the dependency from Maven Central:

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.kotlinds:libretro-kmp:0.1.2")
}
```

## Platform setup

No native dependency is needed: only download the cores you want to use.

- **Android**: ship the core's `.so` for each ABI in your app (e.g. in `jniLibs`, then load it from
  `applicationInfo.nativeLibraryDir`), or download it to the app's files directory.
- **iOS**: apps can only load code signed with the app, so embed cores as frameworks in your bundle and load them
  from there.
- **macOS, Linux, Windows, JVM**: any readable path works.

## Usage

### The frontend — `LibretroFrontend`

A core talks to its frontend (your application) through callbacks. Implement `LibretroFrontend`: only the two
directories are required, everything else has a default.

```kotlin
val frontend = object : LibretroFrontend {
    // Where cores look for BIOS files, and where they write battery saves
    override val systemDirectory = "/path/to/system"
    override val saveDirectory = "/path/to/saves"

    // Core options ("variables"): return null to keep the core's default
    override fun variable(key: String): String? = when (key) {
        "melonds_boot_directly" -> "enabled"
        else -> null
    }

    // A frame is ready: ARGB pixels, whatever the core's pixel format
    override fun onVideoFrame(frame: VideoFrame) {
        display(frame.width, frame.height, frame.pixels)
    }

    // Interleaved stereo 16-bit samples (copy the array if you keep it)
    override fun onAudio(samples: ShortArray, frames: Int) {
        audioOutput.write(samples, frames)
    }

    // Inputs: 1 when the button is pressed
    override fun inputState(port: Int, device: Int, index: Int, id: Int): Short =
        if (port == 0 && device == Device.JOYPAD && id in pressedButtons) 1 else 0

    override fun onLog(level: LogLevel, message: String) = println("[$level] $message")
}
```

### Running a core — `LibretroCore`

```kotlin
val core = LibretroCore("/path/to/melonds_libretro.dylib", frontend)
println("${core.systemInfo.libraryName} ${core.systemInfo.libraryVersion}")

core.loadGame("/path/to/game.nds")
core.setControllerPortDevice(port = 0, device = Device.JOYPAD)
val fps = core.avInfo.timing.fps            // e.g. 59.83 for the Nintendo DS

// One call = one emulated frame; callbacks are invoked during the call.
// Pace it to `fps` for real time, or run it as fast as possible to fast-forward.
while (running) core.run()

// Memory (e.g. for RAM watchers, cheats, bots…)
val ram: ByteArray? = core.readMemory(MemoryType.SYSTEM_RAM)

// Save states
val state = core.saveState()
core.loadState(state!!)

// Always close when done (also lets the core flush its battery save)
core.close()
```

A core must be driven from a single thread, and only one `LibretroCore` may be open at a time in a process (libretro
cores keep global state, and their callbacks carry no user data).

### Inputs

| Device           | `id` values                                                          | Result                                                                    |
|------------------|----------------------------------------------------------------------|---------------------------------------------------------------------------|
| `Device.JOYPAD`  | `JoypadButton.A`, `B`, `X`, `Y`, `L`, `R`, `START`, `SELECT`, D-pad… | `1` when pressed                                                          |
| `Device.POINTER` | `PointerId.X`, `PointerId.Y`, `PointerId.PRESSED`                    | Coordinates in `-0x7FFF..0x7FFF` over the whole frame; `1` while touching |

## API reference

### `LibretroCore`

| Method / Property                                                   | Description                                                      |
|---------------------------------------------------------------------|------------------------------------------------------------------|
| `systemInfo: SystemInfo`                                            | Name, version, supported extensions of the core.                 |
| `avInfo: SystemAvInfo`                                              | Geometry and timings (fps, audio sample rate), after `loadGame`. |
| `loadGame(gamePath: String)`                                        | Loads a game (passed in memory when the core asks for it).       |
| `run()`                                                             | Emulates one frame.                                              |
| `reset()`                                                           | Resets the emulated console.                                     |
| `setControllerPortDevice(port, device)`                             | Plugs a device type in a controller port.                        |
| `memorySize(type)` / `readMemory(type)` / `writeMemory(type, data)` | Access to `SAVE_RAM`, `RTC`, `SYSTEM_RAM`, `VIDEO_RAM`.          |
| `saveState(): ByteArray?` / `loadState(state)`                      | Serializes / restores the whole emulator state.                  |
| `close()`                                                           | Unloads the game and the core. Must be called when done.         |

### `LibretroFrontend`

| Member                              | Description                                      |
|-------------------------------------|--------------------------------------------------|
| `systemDirectory` / `saveDirectory` | Directories given to the core (required).        |
| `variable(key): String?`            | Core option values (null = core default).        |
| `onVideoFrame(frame)`               | ARGB frame ready.                                |
| `onAudio(samples, frames)`          | Interleaved stereo samples ready.                |
| `onInputPoll()` / `inputState(…)`   | Input polling and state queries.                 |
| `onLog(level, message)`             | Core logs.                                       |
| `language`                          | Language reported to the core (default English). |

## Tests

Unit tests run everywhere. An end-to-end test against a real core runs on the JVM and on macOS when these environment
variables are set:

```bash
LIBRETRO_TEST_CORE=/path/to/melonds_libretro.dylib LIBRETRO_TEST_GAME=/path/to/game.nds \
  ./gradlew :libretro-kmp:jvmTest :libretro-kmp:macosArm64Test
```

## License

The Kotlin and C code in this library is licensed under the **Apache License 2.0**.

The bundled `libretro.h` header is © The RetroArch team, under the MIT license (see its header). Cores are not bundled:
each core has its own license (most are GPL), which applies when you distribute it with your application.

See [LICENSE](LICENSE) for details.

## Libraries & tools using libretro-kmp

- [ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon): AIs (decision models, LLMs, MCP agents) playing
  Pokémon games in an emulator.

If you are using libretro-kmp in your project/library, please let us know by opening a pull request to add it to this
list!
