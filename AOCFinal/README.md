# Android Optimization Core 1.2.0

Client-side Forge 1.12.2 optimization core for Android/PojavLauncher-class hardware.

## Target
- Minecraft 1.12.2
- Forge 14.23.5.2864
- Java 8
- OptiFine 1.12.2 HD U G5 (required at runtime)

## Build in GitHub Codespaces

Use a Java 8 environment, then:

```bash
./gradlew setupDecompWorkspace
./gradlew build
```

The JAR is created at:

```text
build/libs/AndroidOptimizationCore-1.2.0.jar
```

The included `gradlew` bootstraps Gradle 4.10.3 if it is not already installed.

## Features

- Entity frustum culling.
- TileEntity frustum culling.
- Configurable visibility radius: 8/16/32/64/128/256.
- Conservative solid-block occlusion.
- Bounded occlusion work per client tick.
- Animated texture throttling.
- Portuguese and English localization.
- AOC menu accessible from Video Settings and F6.
- Fail-open ASM transformer.

## Important implementation choice

AOC does not access Minecraft world/entity state from the worker thread. Ray traces are sampled on the client thread and only cache writes are dispatched to the worker. This is intentionally conservative for legacy 1.12.2 thread-safety.

OptiFine is not declared as a normal Forge dependency because the 1.12.2 G5 distribution is loaded through the legacy OptiFine/LaunchWrapper path. AOC instead validates `Config.VERSION` during pre-initialization and stops with a clear error when G5 is absent or another OptiFine build is present.
