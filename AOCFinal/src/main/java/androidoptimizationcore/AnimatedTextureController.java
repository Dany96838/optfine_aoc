package androidoptimizationcore;

/**
 * Animation compatibility marker.
 *
 * AOC intentionally does not globally skip TextureMap ticks. OptiFine G5's
 * Smart Animations can decide which animated sprites are currently visible.
 * Globally returning early from TextureMap.tick() freezes visible animations
 * and also interferes with OptiFine's sprite bookkeeping.
 */
public final class AnimatedTextureController {
    private AnimatedTextureController() {}
    public static void tick() {}
    public static boolean shouldSkipAtlasUpdate() { return false; }
}
