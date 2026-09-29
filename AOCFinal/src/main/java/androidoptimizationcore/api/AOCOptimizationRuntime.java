package androidoptimizationcore.api;

import java.util.EnumMap;
import java.util.Map;

/**
 * AOC's version-neutral client optimization policy.
 *
 * No Minecraft, Forge, OptiFine, OpenGL or renderer classes are referenced
 * here. Platform-specific adapters ask this runtime for decisions and then
 * perform the actual client-side operation.
 */
public final class AOCOptimizationRuntime {
    private static final AOCOptimizationRuntime INSTANCE =
            new AOCOptimizationRuntime();

    private final Map<AOCOptimizationCategory, Integer> visibilityRanges =
            new EnumMap<AOCOptimizationCategory, Integer>(AOCOptimizationCategory.class);

    private int entityExtraRange;

    private final AOCVisualBudget visualBudget = new AOCVisualBudget(16);

    private boolean entityCulling = true;
    private boolean tileEntityCulling = true;
    private boolean occlusionCulling = true;
    private boolean particleCulling = true;
    private boolean effectCulling = true;

    private AOCOptimizationRuntime() {
        for (AOCOptimizationCategory category : AOCOptimizationCategory.values()) {
            visibilityRanges.put(category, 32);
        }
        entityExtraRange = 0;
    }

    public static AOCOptimizationRuntime get() {
        return INSTANCE;
    }

    public synchronized void setEnabled(
            AOCOptimizationCategory category,
            boolean enabled) {

        switch (category) {
            case ENTITY:
                entityCulling = enabled;
                break;
            case TILE_ENTITY:
                tileEntityCulling = enabled;
                break;
            case PARTICLE:
                particleCulling = enabled;
                break;
            case EFFECT:
                effectCulling = enabled;
                break;
            case BLOCK_VISUAL:
                // Block-visual policy is currently governed by occlusion.
                occlusionCulling = enabled;
                break;
            default:
                break;
        }
    }

    public synchronized boolean isEnabled(AOCOptimizationCategory category) {
        switch (category) {
            case ENTITY: return entityCulling;
            case TILE_ENTITY: return tileEntityCulling;
            case PARTICLE: return particleCulling;
            case EFFECT: return effectCulling;
            case BLOCK_VISUAL: return occlusionCulling;
            default: return false;
        }
    }

    public synchronized void setVisibilityRange(
            AOCOptimizationCategory category,
            int blocks) {

        visibilityRanges.put(category, clamp(blocks, 8, 640));
    }

    public synchronized int getVisibilityRange(
            AOCOptimizationCategory category) {

        Integer value = visibilityRanges.get(category);
        return value == null ? 32 : value;
    }

    public synchronized void setEntityExtraRange(int blocks) {
        entityExtraRange = clamp(blocks, 0, 128);
    }

    public synchronized int getEntityExtraRange() {
        return entityExtraRange;
    }

    public synchronized void configureBudget(int checks) {
        visualBudget.setCapacity(clamp(checks, 0, 64));
    }

    public void resetBudget() {
        visualBudget.reset();
    }

    public boolean tryConsumeVisualCheck() {
        return visualBudget.tryConsume();
    }

    public int getRemainingVisualChecks() {
        return visualBudget.getRemaining();
    }

    public int getVisualCheckCapacity() {
        return visualBudget.getCapacity();
    }

    public synchronized boolean isWithinRange(
            AOCOptimizationCategory category,
            double distanceSquared) {

        int range = getVisibilityRange(category);
        return distanceSquared <= (double) range * (double) range;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
