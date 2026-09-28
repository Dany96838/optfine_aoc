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
    public static int occlusionBudget = 16;

    private AOCConfig() {}

    public static void load(File file) { cfg = new Configuration(file); sync(); }

    public static void sync() {
        if (cfg == null) return;
        entityCulling = cfg.getBoolean("entityCulling", "render", entityCulling,
                "Cull entities outside the camera frustum. Entities remain alive and active.");
        tileEntityCulling = cfg.getBoolean("tileEntityCulling", "render", tileEntityCulling,
                "Cull TileEntities outside the camera frustum. TileEntities remain loaded.");
        occlusionCulling = cfg.getBoolean("occlusionCulling", "render", occlusionCulling,
                "Hide an entity/TileEntity only after solid-block ray checks prove it is fully occluded.");
        animatedTextures = cfg.getBoolean("animatedTextures", "render", animatedTextures,
                "Conservatively throttle the global animated-texture atlas tick.");
        entityDistance = cfg.getInt("entityDistance", "render", entityDistance, 8, 256,
                "Maximum distance at which AOC performs entity visibility checks.");
        tileEntityDistance = cfg.getInt("tileEntityDistance", "render", tileEntityDistance, 8, 256,
                "Maximum distance at which AOC performs TileEntity visibility checks.");
        occlusionBudget = cfg.getInt("occlusionBudget", "render", occlusionBudget, 0, 64,
                "Maximum synchronous world ray checks per client tick.");
        if (cfg.hasChanged()) cfg.save();
    }

    public static void save() { if (cfg != null && cfg.hasChanged()) cfg.save(); }
}
