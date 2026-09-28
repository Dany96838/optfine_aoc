package androidoptimizationcore;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.lang.reflect.Method;

/**
 * OptiFine-compatible animated texture optimization.
 *
 * AOC never globally freezes TextureMap.tick(). Instead, when OptiFine
 * Smart Animations is active, AOC asks OptiFine whether a sprite was rendered
 * recently and skips the sprite's animation update when it is known to be
 * off-screen. Visible animated textures are left completely untouched.
 *
 * If the OptiFine SmartAnimations API is unavailable or throws, AOC fails open
 * and lets normal animation processing continue.
 */
public final class AnimatedTextureController {
    private static Method smartAnimationsActive;
    private static Method spriteRendered;
    private static boolean reflectionReady;
    private static boolean reflectionFailed;

    private AnimatedTextureController() {}

    public static void tick() {
        // Intentionally empty. TextureMap.tick() must never be globally throttled.
    }

    public static boolean shouldSkipAtlasUpdate(TextureAtlasSprite sprite) {
        if (!AOCConfig.animatedTextures || sprite == null) return false;
        if (!ensureOptiFineHooks()) return false;

        try {
            Object active = smartAnimationsActive.invoke(null);
            if (!(active instanceof Boolean) || !((Boolean) active)) {
                return false;
            }

            Object rendered = spriteRendered.invoke(null, sprite);
            return rendered instanceof Boolean && !((Boolean) rendered);
        } catch (Throwable ignored) {
            // Fail open if an OptiFine build/modded sprite behaves differently.
            return false;
        }
    }

    private static boolean ensureOptiFineHooks() {
        if (reflectionReady) return true;
        if (reflectionFailed) return false;

        try {
            Class<?> smart = Class.forName("net.optifine.SmartAnimations");
            smartAnimationsActive = smart.getMethod("isActive");
            spriteRendered = smart.getMethod("isSpriteRendered", TextureAtlasSprite.class);
            reflectionReady = true;
            return true;
        } catch (Throwable ignored) {
            reflectionFailed = true;
            return false;
        }
    }
}
