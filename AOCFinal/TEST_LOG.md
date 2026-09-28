# AOC Test Log

## 2026-09-28 — audit and correction pass

### Repository audit
- Active Gradle project identified as `AOCFinal`.
- Found a second legacy source tree under `aocsrc` containing older AOC implementations.
- Found checked-in Gradle/build artifacts and old version documentation.
- Confirmed the active source and build version are now aligned at 1.2.1.

### Important code corrections
1. Entity hook moved to `RenderManager.shouldRender(Entity, ICamera, double, double, double)` so AOC participates at the actual vanilla entity render decision point.
2. Transformer now patches all matching TileEntity renderer overloads instead of returning after the first match.
3. Transformer now logs successful hook installation and logs a warning when a target method is not found. Previous versions silently returned original bytecode on a missed target, making a non-functional build look healthy.
4. ASM output now uses `COMPUTE_FRAMES | COMPUTE_MAXS` for safer interoperability with other legacy coremods/OptiFine transformations.
5. Entity occlusion cache uses the entity object as the key, avoiding short-lived entity-ID reuse collisions.
6. Occlusion cache remains bounded and invalidates on world/camera/object changes.
7. Block occlusion now distinguishes opaque blocks from transparent/non-opaque blocks. Normal glass is traversed instead of being treated as a solid wall.
8. World access remains on the client thread; no worker thread reads Minecraft world state.

### Compatibility references checked
- Forge 1.12.2 exposes `RenderManager.shouldRender(Entity, ICamera, double, double, double)`.
- Forge 1.12.2 exposes the four TileEntityRendererDispatcher render overloads used by the active transformer.
- Forge 1.12.2 exposes `TextureAtlasSprite.updateAnimation()`.
- Minecraft 1.12.2 uses `World.rayTraceBlocks`; `ClipContext` is not a 1.12.2 API.

### Verification status
- GitHub Actions Java 8 Gradle build: previously passed for 1.2.1; a fresh build is required after this correction pass.
- Minecraft runtime test after this correction pass: not performed in this environment.
- OptiFine G5 runtime test after this correction pass: not performed in this environment.
- FPS improvement: must be measured by the user in the actual target setup; it must not be claimed from compilation alone.
