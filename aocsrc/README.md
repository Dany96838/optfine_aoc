# Android Optimization Core 1.2.0

Client-side Forge 1.12.2 optimization core targeted at Android/Pojav-style hardware.

## Target
- Minecraft 1.12.2
- Forge 14.23.5.2864
- Java 8
- OptiFine HD U G5 (`OptiFine_1.12.2_HD_U_G5`)

## Important compatibility note
OptiFine G5 is a LaunchWrapper tweaker/core transformer, not a conventional Forge mod with a normal `mcmod.info` dependency record. Its own JAR manifest identifies `optifine.OptiFineForgeTweaker`. AOC therefore performs a hard runtime check against `Config.VERSION` and requires exactly `OptiFine_1.12.2_HD_U_G5`. The `mcmod.info` contains the Forge dependency and a non-blocking OptiFine ordering hint; the runtime check is the actual enforcement mechanism.

## What this revision changes
1. Entity and TileEntity frustum culling are injected at the render entry points.
2. Culling is fail-open: if a transformer or culling check fails, the original render path continues.
3. Entities are never removed, unloaded, deactivated, or modified for gameplay. The decision only controls the draw call.
4. Occlusion is conservative. World ray tracing is performed only on the client thread; the worker thread only receives the resulting boolean. This avoids unsafe concurrent access to `World` while still moving cache bookkeeping off the render path.
5. Animated textures are throttled at the `TextureMap.tick()` boundary rather than patching OptiFine's private sprite animation internals. This is deliberately safer with OptiFine G5, but it is a global atlas throttle, not per-sprite distance/FOV culling.
6. F6 opens an English/Portuguese localized configuration screen with tooltips.

## Build with Java 8
Use a real JDK 8 installation for ForgeGradle 2.3. A modern JDK may compile some Java syntax but is not a supported replacement for the complete ForgeGradle 1.12.2 toolchain.

Windows:

```text
set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_XXX
set PATH=%JAVA_HOME%\bin;%PATH%
gradlew setupDecompWorkspace
gradlew build
```

Linux/macOS:

```text
export JAVA_HOME=/path/to/jdk8
gradlew setupDecompWorkspace
gradlew build
```

The final artifact is written to `build/libs/AndroidOptimizationCore-1.2.0.jar`.

## Runtime installation
Put the built AOC JAR in the client `mods` directory together with the exact OptiFine G5 JAR. Do not put AOC on a dedicated server.

## Known scope boundary
The original request asks for asynchronous path-tracing of Minecraft's live world plus per-animation visibility throttling. Doing live `World`/chunk reads from a worker thread in 1.12.2 is not safe and can reproduce the kind of crashes/lag seen in legacy culling implementations. This source intentionally keeps live world reads on the client thread and uses bounded work instead. Legacy EntityCulling 1.12.2 itself documents compatibility/edge-case issues, including a reported chest-related lag case. See the cited research in the accompanying report.
