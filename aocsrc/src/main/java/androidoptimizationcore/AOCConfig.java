package androidoptimizationcore;

import net.minecraftforge.common.config.Configuration;
import java.io.File;

public final class AOCConfig {
    private static Configuration cfg;

    public static boolean entityCulling = true;
    public static boolean tileEntityCulling = true;
    public static boolean occlusionCulling = true;
    public static boolean animatedTextures = true;
    public static int entityDistance = 32;
    public static int tileEntityDistance = 32;
    public static int occlusionBudget = 24;
    public static int animationInterval = 1;

    private AOCConfig() {}

    public static void load(File file) {
        cfg = new Configuration(file);
        sync();
    }

    public static void sync() {
        if (cfg == null) return;
        entityCulling = cfg.getBoolean("entityCulling", "render", entityCulling,
                "Cull entities outside the camera frustum; entities remain alive and active.");
        tileEntityCulling = cfg.getBoolean("tileEntityCulling", "render", tileEntityCulling,
                "Cull TileEntities outside the camera frustum; TileEntities remain loaded.");
        occlusionCulling = cfg.getBoolean("occlusionCulling", "render", occlusionCulling,
                "Hide entities/TileEntities proven to be behind solid blocks.");
        animatedTextures = cfg.getBoolean("animatedTextures", "render", animatedTextures,
                "Throttle animated texture atlas updates.");
        entityDistance = cfg.getInt("entityDistance", "render", entityDistance, 8, 256,
                "Entity culling/check radius in blocks.");
        tileEntityDistance = cfg.getInt("tileEntityDistance", "render", tileEntityDistance, 8, 256,
                "TileEntity culling/check radius in blocks.");
        occlusionBudget = cfg.getInt("occlusionBudget", "render", occlusionBudget, 0, 128,
                "Maximum world ray checks per client tick.");
        animationInterval = cfg.getInt("animationInterval", "render", animationInterval, 1, 4,
                "Animated texture update interval. 1 = normal.");
        if (cfg.hasChanged()) cfg.save();
    }

    public static void save() {
        if (cfg != null && cfg.hasChanged()) cfg.save();
    }
}
