package androidoptimizationcore;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class AOCConfig {
    private static Configuration cfg;

    public static boolean entityCulling = true;
    public static boolean tileEntityCulling = true;
    public static boolean occlusionCulling = true;
    public static int entityDistance = 32;
    public static int tileEntityDistance = 32;
    public static int occlusionBudget = 16;

    private AOCConfig() {}

    public static void load(File file) {
        cfg = new Configuration(file);
        sync();
    }

    public static void sync() {
        if (cfg == null) return;

        entityCulling = cfg.getBoolean(
                "entityCulling", "render", entityCulling,
                "Adds conservative solid-block occlusion to entity rendering. Entities remain alive and active."
        );
        tileEntityCulling = cfg.getBoolean(
                "tileEntityCulling", "render", tileEntityCulling,
                "Frustum-culls TileEntities without unloading or changing them."
        );
        occlusionCulling = cfg.getBoolean(
                "occlusionCulling", "render", occlusionCulling,
                "Hide an entity/TileEntity only when opaque-block visibility checks prove full occlusion."
        );

        entityDistance = cfg.getInt(
                "entityDistance", "render", entityDistance, 8, 512,
                "Maximum distance for AOC entity occlusion checks. Does not change render distance."
        );
        tileEntityDistance = cfg.getInt(
                "tileEntityDistance", "render", tileEntityDistance, 8, 512,
                "Maximum distance for AOC TileEntity occlusion checks. Does not change render distance."
        );
        occlusionBudget = cfg.getInt(
                "occlusionBudget", "render", occlusionBudget, 0, 64,
                "Maximum world ray checks consumed by AOC per client tick."
        );

        if (cfg.hasChanged()) cfg.save();
    }

    public static void resetDefaults() {
        entityCulling = true;
        tileEntityCulling = true;
        occlusionCulling = true;
        entityDistance = 32;
        tileEntityDistance = 32;
        occlusionBudget = 16;
    }

    public static void save() {
        if (cfg == null) return;

        cfg.get("render", "entityCulling", entityCulling).set(entityCulling);
        cfg.get("render", "tileEntityCulling", tileEntityCulling).set(tileEntityCulling);
        cfg.get("render", "occlusionCulling", occlusionCulling).set(occlusionCulling);
        cfg.get("render", "entityDistance", entityDistance).set(entityDistance);
        cfg.get("render", "tileEntityDistance", tileEntityDistance).set(tileEntityDistance);
        cfg.get("render", "occlusionBudget", occlusionBudget).set(occlusionBudget);

        if (cfg.hasChanged()) cfg.save();
    }
}
