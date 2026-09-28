package androidoptimizationcore;

/**
 * Conservative animation throttling. OptiFine owns a large part of the texture
 * animation pipeline in 1.12.2, so AOC intentionally throttles the atlas tick
 * instead of mutating OptiFine's private sprite lists.
 */
public final class AnimatedTextureController {
    private static int tick;
    private static boolean skip;

    private AnimatedTextureController() {}

    public static void tick() {
        if (!AOCConfig.animatedTextures) { skip = false; return; }
        tick++;
        skip = (tick % Math.max(1, AOCConfig.animationInterval)) != 0;
    }

    public static boolean shouldSkipAtlasUpdate() { return skip; }
}
