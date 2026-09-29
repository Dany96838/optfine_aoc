package androidoptimizationcore.api;

/**
 * Categories understood by the AOC client runtime.
 *
 * This API deliberately contains no Minecraft classes. Minecraft/Forge
 * adapters live outside this package so future ports can reuse the same
 * optimization policy.
 */
public enum AOCOptimizationCategory {
    ENTITY,
    ITEM,
    TILE_ENTITY,
    PARTICLE,
    BLOCK_VISUAL,
    EFFECT
}
