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


## 2026-09-29 — user runtime findings incorporated

### Corrections implemented
1. Removed the separate Entity Extra Range option. Entity Distance is now the single entity visibility control with a maximum of 640 blocks.
2. Added a separate Dropped Item option with its own enable/disable control and 8-640 block slider.
3. EntityItem uses the dropped-item range; XP Orb and potion projectile entities remain in the normal entity category.
4. Removed the AOC TileEntity frustum test that could incorrectly remove visible chest rows near the lower part of the screen. TileEntities now use their distance and conservative solid-block occlusion policy.
5. Occlusion cache validity now accounts for camera rotation as well as camera movement, so turning in place can invalidate stale visibility decisions.
6. Particle culling no longer rejects particles using AOC's custom frustum at creation time. It uses distance plus budgeted solid-block occlusion, reducing interference with other particle-monitoring mods.
7. Particle occlusion uses a single center sample to keep the additional cost bounded.
8. GUI option names and the Controls key/category are localized through normal Minecraft translation strings. Internal keys are not displayed.
9. GUI descriptions are delayed by two seconds and slider tooltips include a simple current-impact explanation in Portuguese and English.
10. Lighting simulation was intentionally left untouched; AOC only controls client-side rendering visibility.

### Verification status
- Source changes are committed to the repository.
- GitHub Actions is compiling the staged changes with Java 8 and Forge 14.23.5.2864.
- Final artifact/runtime testing still requires Minecraft/PojavLauncher on the user's device.
