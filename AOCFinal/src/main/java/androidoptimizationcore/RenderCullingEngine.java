package androidoptimizationcore;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Client-side rendering culling for Minecraft 1.12.2.
 *
 * Safety/performance rules:
 * - never removes, unloads, freezes or modifies an Entity/TileEntity;
 * - fail-open whenever AOC cannot prove that an object is invisible;
 * - all World access stays on the Minecraft client thread;
 * - the configured budget counts actual ray traces, not just objects;
 * - the center visibility sample is tested first, avoiding extra rays for
 *   objects that are plainly visible.
 */
public final class RenderCullingEngine {
    private static final Map<Entity, CacheEntry> ENTITY_OCCLUSION =
            new HashMap<Entity, CacheEntry>();
    private static final Map<BlockPos, CacheEntry> TILE_OCCLUSION =
            new HashMap<BlockPos, CacheEntry>();

    private static Frustum tileFrustum =
            new Frustum(ClippingHelperImpl.getInstance());

    private static double camX;
    private static double camY;
    private static double camZ;
    private static World currentWorld;
    private static long tick;
    private static int remainingChecks;

    private static boolean loggedEntityCull;
    private static boolean loggedTileCull;

    private RenderCullingEngine() {}

    public static void updateCamera() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;

        float pt = mc.getRenderPartialTicks();

        camX = camera.prevPosX + (camera.posX - camera.prevPosX) * pt;
        camY = camera.prevPosY + (camera.posY - camera.prevPosY) * pt
                + camera.getEyeHeight();
        camZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * pt;

        Frustum f = new Frustum(ClippingHelperImpl.getInstance());
        f.setPosition(camX, camY, camZ);
        tileFrustum = f;
    }

    /**
     * Hooked into RenderManager.shouldRender().
     * Vanilla keeps its normal distance/frustum decision; AOC only adds
     * conservative solid-block occlusion within the configured range.
     */
    public static boolean shouldCullEntity(
            Entity entity,
            ICamera camera,
            double renderCamX,
            double renderCamY,
            double renderCamZ) {

        try {
            if (!AOCConfig.entityCulling
                    || !AOCConfig.occlusionCulling
                    || entity == null) {
                return false;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;
            if (entity == mc.getRenderViewEntity()) return false;

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null || box.hasNaN()) return false;

            // Do not duplicate vanilla frustum work. This only avoids an
            // unnecessary ray test when vanilla already rejected the box.
            if (camera != null && !entity.ignoreFrustumCheck
                    && !camera.isBoundingBoxInFrustum(box.grow(0.05D))) {
                return false;
            }

            double distance = AOCConfig.entityDistance;
            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return false;
            }

            return isOccluded(
                    mc.world,
                    ENTITY_OCCLUSION,
                    entity,
                    box,
                    null,
                    renderCamX,
                    renderCamY,
                    renderCamZ);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * TileEntities do not receive ICamera in their dispatcher API, so AOC
     * supplies a conservative frustum plus optional solid-block occlusion.
     */
    public static boolean shouldCullTileEntity(TileEntity tileEntity) {
        try {
            if (!AOCConfig.tileEntityCulling || tileEntity == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            BlockPos pos = tileEntity.getPos();
            if (pos == null) return false;

            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            if (box == null) box = new AxisAlignedBB(pos);
            if (box.hasNaN() || box == TileEntity.INFINITE_EXTENT_AABB) return false;

            // Extremely large/custom render bounds are not safe candidates for
            // conservative point sampling.
            if (box.maxX - box.minX > 64.0D
                    || box.maxY - box.minY > 64.0D
                    || box.maxZ - box.minZ > 64.0D) {
                return false;
            }

            if (!tileFrustum.isBoundingBoxInFrustum(box)) {
                if (!loggedTileCull) {
                    loggedTileCull = true;
                    System.out.println("[AOC] TileEntity frustum culling is active.");
                }
                return true;
            }

            if (!AOCConfig.occlusionCulling) {
                return false;
            }

            double dx = pos.getX() + 0.5D - camX;
            double dy = pos.getY() + 0.5D - camY;
            double dz = pos.getZ() + 0.5D - camZ;
            double distance = AOCConfig.tileEntityDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return false;
            }

            boolean result = isOccluded(
                    mc.world,
                    TILE_OCCLUSION,
                    pos,
                    box,
                    pos,
                    camX,
                    camY,
                    camZ);

            if (result && !loggedTileCull) {
                loggedTileCull = true;
                System.out.println("[AOC] TileEntity block-occlusion culling is active.");
            }

            return result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static <K> boolean isOccluded(
            World world,
            Map<K, CacheEntry> cache,
            K key,
            AxisAlignedBB box,
            BlockPos targetBlock,
            double startX,
            double startY,
            double startZ) {

        if (currentWorld != world) {
            currentWorld = world;
            ENTITY_OCCLUSION.clear();
            TILE_OCCLUSION.clear();
            remainingChecks = Math.max(0, AOCConfig.occlusionBudget);
        }

        CacheEntry cached = cache.get(key);
        if (cached != null
                && cached.isUsable(tick, startX, startY, startZ, box)) {
            return cached.occluded;
        }

        if (remainingChecks <= 0) {
            return false;
        }

        OcclusionResult result = computeOcclusion(
                world, box, targetBlock, startX, startY, startZ);

        // Budget exhaustion is not evidence of visibility or occlusion.
        // Do not cache that fail-open decision.
        if (result == OcclusionResult.BUDGET_EXHAUSTED) {
            return false;
        }

        cache.put(
                key,
                new CacheEntry(
                        result == OcclusionResult.OCCLUDED,
                        tick,
                        startX,
                        startY,
                        startZ,
                        box));

        if (result == OcclusionResult.OCCLUDED
                && key instanceof Entity
                && !loggedEntityCull) {
            loggedEntityCull = true;
            System.out.println("[AOC] Entity block-occlusion culling is active.");
        }

        return result == OcclusionResult.OCCLUDED;
    }

    public static void endClientTick() {
        tick++;
        updateCamera();

        remainingChecks = Math.max(0, AOCConfig.occlusionBudget);

        // Prevent long-session memory growth without doing per-frame map work.
        if (tick % 40L == 0L) {
            if (ENTITY_OCCLUSION.size() > 8192) ENTITY_OCCLUSION.clear();
            if (TILE_OCCLUSION.size() > 8192) TILE_OCCLUSION.clear();
        }
    }

    /**
     * Samples the center first, then the eight inset corners. An object is
     * culled only if every sampled point is blocked by an opaque cube.
     */
    private static OcclusionResult computeOcclusion(
            World world,
            AxisAlignedBB box,
            BlockPos targetBlock,
            double startX,
            double startY,
            double startZ) {

        double ex = Math.min(0.05D,
                Math.max(0.001D, (box.maxX - box.minX) * 0.25D));
        double ey = Math.min(0.05D,
                Math.max(0.001D, (box.maxY - box.minY) * 0.25D));
        double ez = Math.min(0.05D,
                Math.max(0.001D, (box.maxZ - box.minZ) * 0.25D));

        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;

        double[][] samples = new double[][] {
                {cx, cy, cz},
                {box.minX + ex, box.minY + ey, box.minZ + ez},
                {box.maxX - ex, box.minY + ey, box.minZ + ez},
                {box.minX + ex, box.maxY - ey, box.minZ + ez},
                {box.maxX - ex, box.maxY - ey, box.minZ + ez},
                {box.minX + ex, box.minY + ey, box.maxZ - ez},
                {box.maxX - ex, box.minY + ey, box.maxZ - ez},
                {box.minX + ex, box.maxY - ey, box.maxZ - ez},
                {box.maxX - ex, box.maxY - ey, box.maxZ - ez}
        };

        BlockPos cameraBlock = new BlockPos(startX, startY, startZ);

        for (double[] sample : samples) {
            RayResult ray = rayBlockedByOpaqueBlock(
                    world,
                    startX, startY, startZ,
                    sample[0], sample[1], sample[2],
                    cameraBlock,
                    targetBlock);

            if (ray == RayResult.BUDGET_EXHAUSTED) {
                return OcclusionResult.BUDGET_EXHAUSTED;
            }

            if (ray == RayResult.VISIBLE) {
                return OcclusionResult.VISIBLE;
            }
        }

        return OcclusionResult.OCCLUDED;
    }

    /**
     * Performs one world ray trace. A non-opaque first hit is traversed so
     * ordinary glass does not become a false wall. The budget is consumed
     * once per actual ray trace.
     */
    private static RayResult rayBlockedByOpaqueBlock(
            World world,
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            BlockPos cameraBlock,
            BlockPos targetBlock) {

        if (remainingChecks <= 0) {
            return RayResult.BUDGET_EXHAUSTED;
        }
        remainingChecks--;

        double sx = startX;
        double sy = startY;
        double sz = startZ;

        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (length < 0.0001D) return RayResult.VISIBLE;

        final int maxTransparentHits = 8;
        final double advance = 0.002D;

        for (int i = 0; i < maxTransparentHits; i++) {
            RayTraceResult hit = world.rayTraceBlocks(
                    new Vec3d(sx, sy, sz),
                    new Vec3d(endX, endY, endZ),
                    false,
                    true,
                    false);

            if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
                return RayResult.VISIBLE;
            }

            BlockPos hitPos = hit.getBlockPos();
            if (hitPos == null) return RayResult.VISIBLE;

            if (targetBlock != null && targetBlock.equals(hitPos)) {
                return RayResult.VISIBLE;
            }

            if (cameraBlock.equals(hitPos)) {
                return RayResult.VISIBLE;
            }

            IBlockState state = world.getBlockState(hitPos);

            if (state == null || !state.isOpaqueCube()) {
                if (hit.hitVec == null) return RayResult.VISIBLE;

                double nx = dx / length;
                double ny = dy / length;
                double nz = dz / length;

                sx = hit.hitVec.x + nx * advance;
                sy = hit.hitVec.y + ny * advance;
                sz = hit.hitVec.z + nz * advance;

                double rx = endX - sx;
                double ry = endY - sy;
                double rz = endZ - sz;

                if (rx * dx + ry * dy + rz * dz <= 0.0D) {
                    return RayResult.VISIBLE;
                }

                continue;
            }

            return RayResult.BLOCKED;
        }

        // Too many transparent surfaces are not proof of occlusion.
        return RayResult.VISIBLE;
    }

    private enum RayResult {
        BLOCKED,
        VISIBLE,
        BUDGET_EXHAUSTED
    }

    private enum OcclusionResult {
        OCCLUDED,
        VISIBLE,
        BUDGET_EXHAUSTED
    }

    private static final class CacheEntry {
        final boolean occluded;
        final long tick;
        final double x;
        final double y;
        final double z;

        final double minX;
        final double minY;
        final double minZ;
        final double maxX;
        final double maxY;
        final double maxZ;

        CacheEntry(
                boolean occluded,
                long tick,
                double x,
                double y,
                double z,
                AxisAlignedBB box) {

            this.occluded = occluded;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;

            this.minX = box.minX;
            this.minY = box.minY;
            this.minZ = box.minZ;
            this.maxX = box.maxX;
            this.maxY = box.maxY;
            this.maxZ = box.maxZ;
        }

        boolean isUsable(
                long now,
                double cameraX,
                double cameraY,
                double cameraZ,
                AxisAlignedBB box) {

            if (now - tick > 4L) return false;

            double dx = cameraX - x;
            double dy = cameraY - y;
            double dz = cameraZ - z;

            if (dx * dx + dy * dy + dz * dz > 2.25D) {
                return false;
            }

            return Math.abs(minX - box.minX) < 0.05D
                    && Math.abs(minY - box.minY) < 0.05D
                    && Math.abs(minZ - box.minZ) < 0.05D
                    && Math.abs(maxX - box.maxX) < 0.05D
                    && Math.abs(maxY - box.maxY) < 0.05D
                    && Math.abs(maxZ - box.maxZ) < 0.05D;
        }
    }
}
