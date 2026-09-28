package androidoptimizationcore;

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
