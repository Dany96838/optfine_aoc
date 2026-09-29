# Android Optimization Core 1.4.0

Independent client-side optimization mod for Minecraft 1.12.2 / Forge 14.23.5.2864.

AOC is designed to improve rendering performance, especially on Android/PojavLauncher-class hardware, while remaining usable on PC. It does **not** require OptiFine and does not call OptiFine APIs. When OptiFine is installed, AOC remains an independent Forge coremod and is intended to coexist with it.

## Target
- Minecraft 1.12.2
- Forge 14.23.5.2864
- Java 8
- OptiFine: optional

## Main optimization path
- Entity occlusion hook at the vanilla `RenderManager.shouldRender` decision point.
- TileEntity distance and conservative solid-block occlusion at `TileEntityRendererDispatcher` render overloads.
- Solid-block occlusion using Minecraft 1.12.2 `World.rayTraceBlocks`.
- Center sample first, followed by inset corner samples only when necessary.
- Normal non-opaque blocks such as ordinary glass are not treated as solid walls.
- Fail-open behavior whenever visibility cannot be proven.
- Actual ray-trace budget per client tick.
- Short-lived cache for repeated visibility decisions.
- No entity deletion, unloading, freezing, chunk removal, or server/game-state modification.

## Settings
Press F4 to open AOC settings.

- Entity Culling
- Dropped Item Culling
- TileEntity Culling
- Particle Optimization
- Visual Effects Optimization
- Block Occlusion
- Entity Visibility Range: 8-640 blocks
- Dropped Item Distance: 8-640 blocks
- TileEntity Visibility Range: 8-640 blocks
- Particle Distance: 8-160 blocks
- Visual Effects Distance: 8-160 blocks
- Occlusion Checks/Tick: 0-64

The numeric controls are draggable Forge 1.12.2 sliders. Visibility ranges are client-side visual limits. They do not delete entities, change server state, or change Minecraft's world/chunk simulation distance.

## Build
From `AOCFinal` with Java 8:

```bash
./gradlew clean build --no-daemon
```

The final JAR is:

```text
build/libs/AndroidOptimizationCore-1.4.0.jar
```

A successful build verifies compilation and packaging. It does not by itself prove FPS improvement; runtime FPS and culling behavior must still be measured in Minecraft 1.12.2 on the target device/modpack.

## Runtime diagnostics
The transformer prints explicit `[AOC] PATCHED ...` messages for the RenderManager, TileEntityRendererDispatcher, and ParticleManager hooks. If a target cannot be found, it prints an explicit warning and leaves the original class untouched.

## Repository structure
`AOCFinal` is the active Gradle project. Legacy sources/artifacts are not part of the active build.
