# AOC Test Log

## 2026-09-28 — independent architecture audit

### Problems found and corrected
1. The mod still required OptiFine G5 at preInit. Removed the runtime requirement completely.
2. The transformer depended on deobfuscated class names inside method descriptors. This can miss production 1.12.2 bytecode. Entity and TileEntity targets now use descriptor shape matching that does not depend on those internal class names.
3. The animated-texture hook depended on OptiFine SmartAnimations. Removed it from the independent build instead of keeping an OptiFine API dependency hidden behind reflection.
4. The entity hook now remains at RenderManager.shouldRender and adds only conservative solid-block occlusion; vanilla retains its normal render-distance/frustum decision.
5. TileEntity render overload matching now covers the overloads by parameter shape.
6. Occlusion budgeting now counts actual World.rayTraceBlocks calls, including additional traces through transparent blocks.
7. Budget exhaustion is fail-open and is not cached as a visibility decision.
8. The first visibility sample is the object center. Corner samples are only reached when the center is blocked.
9. The settings screen now contains only independent AOC features; no OptiFine-only option remains.
10. The mod description, README and source manifest now describe AOC as an independent optimizer.

### API checks
- Forge 1.12.2 provides RenderManager.shouldRender(Entity, ICamera, double, double, double).
- Forge 1.12.2 provides four TileEntityRendererDispatcher.render overloads.
- Minecraft 1.12.2 uses World.rayTraceBlocks; ClipContext is not used.
- Forge Configuration supports the integer ranges used by the GUI/config.

### Build/runtime status
- Fresh post-change Gradle build: pending final workflow result.
- Minecraft runtime test after this audit: not available in this environment.
- FPS improvement: not claimed until measured on the target device/modpack.
