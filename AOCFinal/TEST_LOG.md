# AOC Test Log

## 2026-09-28

### 1. Build environment
- Local execution environment could not run the ForgeGradle build because Java 8 is unavailable and network resolution is restricted.
- The repository contains a previously generated `build/libs/AndroidOptimizationCore-1.2.0.jar`, confirming that the pre-change project had been built successfully at least once.
- Added `.github/workflows/aoc-build.yml` to provide a clean Java 8 CI build. No workflow run is currently exposed by the connected GitHub API, so CI is not counted as passed.

### 2. Occlusion cache — fixed
Problem:
- Cache entries were keyed only by entity ID / block position.
- Results could remain valid after camera movement, entity movement, or world changes.
- The same entity ID could be reused in another world.
- Repeated render calls could enqueue duplicate requests.
- The code used a worker thread only to write a boolean into a map after the expensive ray traces had already run on the client thread.

Fix:
- Cache now expires after 4 client ticks.
- Cache is invalidated when the camera moves more than 1.5 blocks or the object's bounding box changes.
- Cache is cleared when the active world changes.
- Added a pending-request set to prevent duplicate queue entries.
- Removed the unnecessary worker thread; results are now published deterministically on the client thread.

### 3. Animated texture hook — fixed
Problem:
- The transformer looked for `TextureMap.tick` / `func_73660_a`.
- Minecraft 1.12.2's animated texture update method is `TextureMap.func_94248_c`.
- Therefore the AOC animated-texture throttle was not being injected.

Fix:
- Transformer now recognizes `func_94248_c` while retaining the fallback names.

### 4. Video Settings button — fixed
Problem:
- AOC used a hard-coded Y coordinate in Video Settings.
- This could overlap or separate from OptiFine's Reset Video Settings button depending on screen layout.

Fix:
- AOC now searches for OptiFine's reset button (ID 210 / known label) and places the AOC button immediately beside it.
- A safe fallback position remains for environments where the OptiFine button is not found.

### 5. Android Render Monitor
Status:
- No Android Render Monitor source/classes are present in the GitHub repository tree.
- The current AOC project therefore cannot yet be verified as containing the previously validated Monitor implementation.
- This remains an open integration item and is not marked as passed.

## Current status
- Static code corrections applied: YES
- Clean Java 8 build after corrections: NOT YET VERIFIED
- Forge client startup test after corrections: NOT YET VERIFIED
- OptiFine G5 runtime test: NOT YET VERIFIED
- F4 Monitor integration test: NOT YET VERIFIED
