package androidoptimizationcore;

import androidoptimizationcore.api.AOCOptimizationCategory;
import androidoptimizationcore.api.AOCOptimizationRuntime;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class AOCConfig {
    private static Configuration cfg;

    public static boolean entityCulling = true;
    public static boolean itemCulling = true;
    public static boolean tileEntityCulling = true;
    public static boolean occlusionCulling = true;
    public static boolean particleCulling = true;
    public static boolean effectCulling = true;

    public static int entityDistance = 32;
    public static int itemDistance = 32;
    public static int tileEntityDistance = 32;
    public static int particleDistance = 32;
    public static int effectDistance = 32;
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
                "Client-side entity visibility optimization. Does not change entity logic."
        );
        itemCulling = cfg.getBoolean(
                "itemCulling", "render", itemCulling,
                "Client-side dropped-item visibility optimization."
        );
        tileEntityCulling = cfg.getBoolean(
                "tileEntityCulling", "render", tileEntityCulling,
                "Client-side TileEntity visibility optimization."
        );
        occlusionCulling = cfg.getBoolean(
                "occlusionCulling", "render", occlusionCulling,
                "Client-side opaque-block visibility checks."
        );
        particleCulling = cfg.getBoolean(
                "particleCulling", "render", particleCulling,
                "Client-side particle visibility optimization."
        );
        effectCulling = cfg.getBoolean(
                "effectCulling", "render", effectCulling,
                "Client-side visual-effect visibility optimization."
        );

        entityDistance = cfg.getInt(
                "entityDistance", "render", entityDistance, 8, 640,
                "Maximum AOC client-side entity visibility range."
        );
        itemDistance = cfg.getInt(
                "itemDistance", "render", itemDistance, 8, 640,
                "Maximum AOC client-side dropped-item visibility range."
        );
        tileEntityDistance = cfg.getInt(
                "tileEntityDistance", "render", tileEntityDistance, 8, 640,
                "Maximum AOC client-side TileEntity visibility range."
        );
        particleDistance = cfg.getInt(
                "particleDistance", "render", particleDistance, 8, 160,
                "Maximum AOC client-side particle visibility range."
        );
        effectDistance = cfg.getInt(
                "effectDistance", "render", effectDistance, 8, 160,
                "Maximum AOC client-side visual-effect visibility range."
        );
        occlusionBudget = cfg.getInt(
                "occlusionBudget", "render", occlusionBudget, 0, 64,
                "Maximum optional AOC world visibility checks per client tick."
        );

        applyToRuntime();

        if (cfg.hasChanged()) cfg.save();
    }

    public static void applyToRuntime() {
        AOCOptimizationRuntime runtime = AOCOptimizationRuntime.get();

        runtime.setEnabled(AOCOptimizationCategory.ENTITY, entityCulling);
        runtime.setEnabled(AOCOptimizationCategory.ITEM, itemCulling);
        runtime.setEnabled(AOCOptimizationCategory.TILE_ENTITY, tileEntityCulling);
        runtime.setEnabled(AOCOptimizationCategory.BLOCK_VISUAL, occlusionCulling);
        runtime.setEnabled(AOCOptimizationCategory.PARTICLE, particleCulling);
        runtime.setEnabled(AOCOptimizationCategory.EFFECT, effectCulling);

        runtime.setVisibilityRange(
                AOCOptimizationCategory.ENTITY, entityDistance);
        runtime.setVisibilityRange(
                AOCOptimizationCategory.ITEM, itemDistance);
        runtime.setVisibilityRange(
                AOCOptimizationCategory.TILE_ENTITY, tileEntityDistance);
        runtime.setVisibilityRange(
                AOCOptimizationCategory.PARTICLE, particleDistance);
        runtime.setVisibilityRange(
                AOCOptimizationCategory.EFFECT, effectDistance);

        runtime.configureBudget(occlusionBudget);
    }

    public static void resetDefaults() {
        entityCulling = true;
        itemCulling = true;
        tileEntityCulling = true;
        occlusionCulling = true;
        particleCulling = true;
        effectCulling = true;

        entityDistance = 32;
        itemDistance = 32;
        tileEntityDistance = 32;
        particleDistance = 32;
        effectDistance = 32;
        occlusionBudget = 16;

        applyToRuntime();
    }

    public static void save() {
        if (cfg == null) return;

        cfg.get("render", "entityCulling", entityCulling).set(entityCulling);
        cfg.get("render", "itemCulling", itemCulling).set(itemCulling);
        cfg.get("render", "tileEntityCulling", tileEntityCulling).set(tileEntityCulling);
        cfg.get("render", "occlusionCulling", occlusionCulling).set(occlusionCulling);
        cfg.get("render", "particleCulling", particleCulling).set(particleCulling);
        cfg.get("render", "effectCulling", effectCulling).set(effectCulling);

        cfg.get("render", "entityDistance", entityDistance).set(entityDistance);
        cfg.get("render", "itemDistance", itemDistance).set(itemDistance);
        cfg.get("render", "tileEntityDistance", tileEntityDistance).set(tileEntityDistance);
        cfg.get("render", "particleDistance", particleDistance).set(particleDistance);
        cfg.get("render", "effectDistance", effectDistance).set(effectDistance);
        cfg.get("render", "occlusionBudget", occlusionBudget).set(occlusionBudget);

        applyToRuntime();

        if (cfg.hasChanged()) cfg.save();
    }
}
