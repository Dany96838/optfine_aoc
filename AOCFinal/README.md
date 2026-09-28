Android Optimization Core 1.2.1

Client-side Forge 1.12.2 rendering optimization core for Android/PojavLauncher-class hardware and PC.

## Exact target
- Minecraft 1.12.2
- Forge 14.23.5.2864
- Java 8
- OptiFine 1.12.2 HD U G5 (validated at runtime)

## Build
From `AOCFinal` with Java 8:

```bash
./gradlew clean build --no-daemon
```

The compiled JAR is:

```text
build/libs/AndroidOptimizationCore-1.2.1.jar
```

## Rendering design
- Entity culling is injected into `RenderManager.shouldRender`, using the current vanilla `ICamera` and camera coordinates.
- TileEntity culling covers all matching `TileEntityRendererDispatcher.render` overloads.
- Block occlusion is fail-open: AOC hides an object only when all visibility samples are blocked by opaque blocks.
- Normal glass/non-opaque blocks are treated as transparent during the visibility test.
- Entities and TileEntities are never removed, unloaded, frozen, or changed in server/game state.
- Occlusion ray work is synchronous and bounded by `Occlusion Checks/Tick`.
- Cache entries expire when the camera/object changes and are bounded in size.
- Transformer failures are logged and fail open instead of silently pretending that a hook was installed.

## Configuration
F6 opens AOC settings. Numeric values are draggable Forge 1.12.2 sliders. Visibility ranges limit only AOC's extra occlusion checks; they do not change Minecraft/OptiFine render distance.

## Repository layout
`AOCFinal` is the only active Gradle project. Older `aocsrc` files and checked-in build artifacts are legacy/reference material and are not part of the active Gradle source set.

## Runtime verification
A successful Gradle build proves compilation/package integrity, not Minecraft runtime behavior. Runtime testing must be performed with Forge 14.23.5.2864, OptiFine G5, and the intended modpack.
