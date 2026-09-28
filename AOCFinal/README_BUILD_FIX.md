# AOC 1.2.0 - Forge 14.23.5.2864 build configuration

This source uses ForgeGradle 3 because Forge 1.12.2-14.23.5.2864 is published as
UserDev 3. The previous ForgeGradle 2.3 configuration requested the obsolete
`forge-1.12.2-14.23.5.2864-userdev.jar` artifact and therefore failed during
`extractUserdev`.

## Environment

- Minecraft: 1.12.2
- Forge: 14.23.5.2864
- ForgeGradle: 3.x
- Mappings: stable 39 / `39-1.12`
- Java: 8
- Gradle bootstrap: 4.10.3

## Build

```bash
./gradlew tasks
./gradlew build
```

The final production JAR is generated in:

```text
build/libs/
```

Do not use the old ForgeGradle 2.3 `setupDecompWorkspace` workflow with this
configuration. ForgeGradle 3 manages the UserDev 3 setup through the `minecraft`
dependency and its normal Gradle tasks.
