package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side renderer culling for Minecraft 1.12.2.
 *
 * Important: this class uses the 1.12.2 World#rayTraceBlocks API.
 * ClipContext does not exist in Minecraft 1.12.2.
 *
 * Culling is fail-open:
 * - entities/TileEntities are never removed or unloaded;
 * - a failed or budget-exhausted visibility check renders normally;
 * - only a fresh result proving that every visibility sample is blocked
 *   can suppress rendering.
 *
 * World access remains on the Minecraft client thread. No worker thread
 * touches Minecraft world state.
 */
public final class RenderCullingEngine {
    private static final Map<Long, CacheEntry> OCCLUSION =
            new ConcurrentHashMap<Long, CacheEntry>();

    private static Frustum frustum =
            new Frustum(ClippingHelperImpl.getInstance());

    private static double camX;
    private static double camY;
    private static double camZ;
    private static World currentWorld;
    private static long tick;

    /**
     * Remaining synchronous occlusion checks for the current client tick.
     * A check is one complete 9-sample visibility test.
     */
    private static int remainingChecks;

    private RenderCullingEngine() {}

    public static void updateCamera() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;

        float pt = mc.getRenderPartialTicks();

        camX = camera.prevPosX
                + (camera.posX - camera.prevPosX) * pt;
        camY = camera.prevPosY
                + (camera.posY - camera.prevPosY) * pt
                + camera.getEyeHeight();
        camZ = camera.prevPosZ
                + (camera.posZ - camera.prevPosZ) * pt;

        Frustum f = new Frustum(ClippingHelperImpl.getInstance());
        f.setPosition(camX, camY, camZ);
        frustum = f;
    }

    public static boolean shouldCullEntity(Entity entity) {
        try {
            if (!AOCConfig.entityCulling || entity == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            if (entity == mc.getRenderViewEntity()) return false;

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null) return false;

            // Frustum culling is independent of block occlusion.
            if (!frustum.isBoundingBoxInFrustum(box.grow(0.05D))) {
                return true;
            }

            if (!AOCConfig.occlusionCulling) return false;

            double distance = AOCConfig.entityDistance;
            if (entity.getDistanceSq(camX, camY, camZ) > distance * distance) {
                return false;
            }

            return isOccluded(mc.world, entityKey(entity), box, null);
        } catch (Throwable ignored) {
            // A renderer optimization must never break another mod's entity.
            return false;
        }
    }

    public static boolean shouldCullTileEntity(TileEntity tileEntity) {
        try {
            if (!AOCConfig.tileEntityCulling || tileEntity == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            BlockPos pos = tileEntity.getPos();
            if (pos == null) return false;

            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            if (box == null) {
                box = new AxisAlignedBB(pos);
            }

            if (!frustum.isBoundingBoxInFrustum(box)) {
                return true;
            }

            if (!AOCConfig.occlusionCulling
                    || box == TileEntity.INFINITE_EXTENT_AABB
                    || Math.abs(box.maxX - box.minX) > 64.0D
                    || Math.abs(box.maxY - box.minY) > 64.0D
                    || Math.abs(box.maxZ - box.minZ) > 64.0D) {
                return false;
            }

            double dx = pos.getX() + 0.5D - camX;
            double dy = pos.getY() + 0.5D - camY;
            double dz = pos.getZ() + 0.5D - camZ;
            double distance = AOCConfig.tileEntityDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return false;
            }

            return isOccluded(mc.world, tileKey(tileEntity), box, pos);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long entityKey(Entity entity) {
        return 0x4000000000000000L
                | (entity.getEntityId() & 0x3FFFFFFFL);
    }

    private static long tileKey(TileEntity tileEntity) {
        BlockPos pos = tileEntity.getPos();
        return ((long) pos.getX() * 341873128712L)
                ^ ((long) pos.getY() * 132897987541L)
                ^ ((long) pos.getZ() * 42317861L);
    }

    /**
     * Returns true only when a fresh cache entry proves complete occlusion.
     * If there is no cache entry and budget remains, the test is performed
     * synchronously during rendering. This removes the old one-tick blind
     * window where every new object necessarily rendered once before being
     * tested.
     */
    private static boolean isOccluded(
            World world,
            long key,
            AxisAlignedBB box,
            BlockPos targetBlock) {

        if (currentWorld != world) {
            currentWorld = world;
            OCCLUSION.clear();
            remainingChecks = Math.max(0, AOCConfig.occlusionBudget);
        }

        CacheEntry cached = OCCLUSION.get(key);
        if (cached != null
                && cached.isUsable(tick, camX, camY, camZ, box)) {
            return cached.occluded;
        }

        if (remainingChecks <= 0) {
            // No proof means no culling.
            return false;
        }

        remainingChecks--;

        boolean result = computeOcclusion(
                world, box, targetBlock, camX, camY, camZ);

        OCCLUSION.put(
                key,
                new CacheEntry(
                        result,
                        tick,
                        camX,
                        camY,
                        camZ,
                        box
                )
        );

        return result;
    }

    /**
     * Called once at the end of every client tick.
     *
     * The budget is prepared for the following render period. There is no
     * asynchronous world access and no queued world work.
     */
    public static void endClientTick() {
        tick++;

        updateCamera();

        remainingChecks = Math.max(0, AOCConfig.occlusionBudget);

        if (tick % 40L == 0L && OCCLUSION.size() > 8192) {
            OCCLUSION.clear();
        }
    }

    /**
     * Minecraft 1.12.2 block line-of-sight test.
     *
     * There is no ClipContext in 1.12.2. World#rayTraceBlocks is the correct
     * API for this version.
     *
     * Nine samples are tested: center plus the eight near-corners of the
     * bounding box. The object is considered occluded only if every sample
     * hits a blocking block before reaching the object.
     */
    private static boolean computeOcclusion(
            World world,
            AxisAlignedBB box,
            BlockPos targetBlock,
            double startX,
            double startY,
            double startZ) {

        double ex = Math.min(
                0.05D,
                Math.max(0.001D, (box.maxX - box.minX) * 0.25D)
        );
        double ey = Math.min(
                0.05D,
                Math.max(0.001D, (box.maxY - box.minY) * 0.25D)
        );
        double ez = Math.min(
                0.05D,
                Math.max(0.001D, (box.maxZ - box.minZ) * 0.25D)
        );

        double centerX = (box.minX + box.maxX) * 0.5D;
        double centerY = (box.minY + box.maxY) * 0.5D;
        double centerZ = (box.minZ + box.maxZ) * 0.5D;

        double[][] samples = new double[][] {
                {centerX, centerY, centerZ},

                {box.minX + ex, box.minY + ey, box.minZ + ez},
                {box.maxX - ex, box.minY + ey, box.minZ + ez},
                {box.minX + ex, box.maxY - ey, box.minZ + ez},
                {box.maxX - ex, box.maxY - ey, box.minZ + ez},

                {box.minX + ex, box.minY + ey, box.maxZ - ez},
                {box.maxX - ex, box.minY + ey, box.maxZ - ez},
                {box.minX + ex, box.maxY - ey, box.maxZ - ez},
                {box.maxX - ex, box.maxY - ey, box.maxZ - ez}
        };

        BlockPos cameraBlock =
                new BlockPos(startX, startY, startZ);

        for (double[] sample : samples) {
            RayTraceResult hit = world.rayTraceBlocks(
                    new Vec3d(startX, startY, startZ),
                    new Vec3d(sample[0], sample[1], sample[2]),
                    false,
                    true,
                    false
            );

            if (hit == null
                    || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
                return false;
            }

            BlockPos hitPos = hit.getBlockPos();

            // A TileEntity's own block is the target, not an occluder.
            if (targetBlock != null && targetBlock.equals(hitPos)) {
                return false;
            }

            // Never let the block containing the camera count as a wall.
            if (cameraBlock.equals(hitPos)) {
                return false;
            }
        }

        return true;
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
