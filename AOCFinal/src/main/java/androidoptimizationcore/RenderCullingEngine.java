package androidoptimizationcore;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.particle.Particle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityAreaEffectCloud;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Client-side visibility policy and work scheduler for Minecraft 1.12.2.
 *
 * Expensive world ray traces are never performed from the render hook.
 * Render hooks only consult a small cache and enqueue missing visibility
 * work. The client tick processes that queue under the configured budget.
 *
 * Nothing here removes, unloads, freezes, or changes an Entity/TileEntity.
 */
public final class RenderCullingEngine {
    private static final Map<Entity, CacheEntry> ENTITY_OCCLUSION =
            new HashMap<Entity, CacheEntry>();
    private static final Map<BlockPos, CacheEntry> TILE_OCCLUSION =
            new HashMap<BlockPos, CacheEntry>();

    private static final Map<Entity, OcclusionTask<Entity>> ENTITY_PENDING =
            new HashMap<Entity, OcclusionTask<Entity>>();
    private static final Map<BlockPos, OcclusionTask<BlockPos>> TILE_PENDING =
            new HashMap<BlockPos, OcclusionTask<BlockPos>>();

    private static final ArrayDeque<OcclusionTask<?>> WORK_QUEUE =
            new ArrayDeque<OcclusionTask<?>>();
    private static final Set<Object> QUEUED =
            new HashSet<Object>();

    private static Frustum tileFrustum =
            new Frustum(ClippingHelperImpl.getInstance());

    private static double camX;
    private static double camY;
    private static double camZ;
    private static World currentWorld;
    private static long tick;

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
     *
     * The render hook performs only cheap checks. Missing occlusion data is
     * fail-open for the current frame and is computed later on the client
     * tick, preventing ray tracing from becoming a frame-time spike.
     */
    public static boolean shouldCullEntity(
            Entity entity,
            ICamera camera,
            double renderCamX,
            double renderCamY,
            double renderCamZ) {

        try {
            if (!AOCConfig.entityCulling || entity == null) {
                return false;
            }

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;
            if (entity == mc.getRenderViewEntity()) return false;

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null || box.hasNaN()) return false;

            if (camera != null && !entity.ignoreFrustumCheck
                    && !camera.isBoundingBoxInFrustum(box.grow(0.05D))) {
                return false;
            }

            double distance = AOCConfig.entityDistance;
            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return true;
            }

            if (!AOCConfig.occlusionCulling) return false;

            return requestOcclusion(
                    mc.world,
                    ENTITY_OCCLUSION,
                    ENTITY_PENDING,
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
     * supplies a conservative frustum plus asynchronous solid-block
     * occlusion.
     */
    /**
     * Extends the vanilla entity render-distance decision without changing
     * entity state. This is consulted only when vanilla returned false.
     *
     * Vanilla 1.12.2 uses the entity bounding-box average edge length,
     * multiplied by 64 and renderDistanceWeight (1.0), as its base distance.
     * AOC adds only the separately configured 0..128 block extension.
     */
    public static boolean shouldForceExtendedEntityRender(
            Entity entity,
            ICamera camera,
            double renderCamX,
            double renderCamY,
            double renderCamZ) {
        try {
            if (entity == null) return false;

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null || box.hasNaN()) return false;

            if (camera != null && !entity.ignoreFrustumCheck
                    && !camera.isBoundingBoxInFrustum(box)) {
                return false;
            }

            double edge = box.getAverageEdgeLength();
            if (Double.isNaN(edge)) edge = 1.0D;

            double vanillaRange = edge * 64.0D;
            double allowed = Math.max(
                    vanillaRange, (double) AOCConfig.entityDistance)
                    + (double) AOCConfig.entityExtraRange;

            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            double aocLimit = (double) AOCConfig.entityDistance
                    + (double) AOCConfig.entityExtraRange;

            return distanceSq < allowed * allowed
                    && distanceSq < aocLimit * aocLimit;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Dedicated visual-effect culling for effect-like entities. This remains
     * separate from the generic entity switch so users can optimize effects
     * without changing the entity-culling policy.
     */
    public static boolean shouldCullVisualEffect(
            Entity entity,
            ICamera camera,
            double renderCamX,
            double renderCamY,
            double renderCamZ) {
        try {
            if (!AOCConfig.effectCulling || entity == null) return false;

            if (!(entity instanceof EntityAreaEffectCloud)
                    && !(entity instanceof EntityLightningBolt)
                    && !(entity instanceof EntityFireworkRocket)) {
                return false;
            }

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null || box.hasNaN()) return false;

            if (camera != null && !entity.ignoreFrustumCheck
                    && !camera.isBoundingBoxInFrustum(box.grow(0.05D))) {
                return true;
            }

            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;
            double distance = AOCConfig.effectDistance;

            return dx * dx + dy * dy + dz * dz > distance * distance;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * ParticleManager hook. Particles beyond the configured client-side
     * visibility range or outside the camera frustum are never queued into
     * the particle manager. This is visual-only; it does not touch world
     * state or the source event that requested the particle.
     */
    public static boolean shouldCullParticle(Particle particle) {
        try {
            if (!AOCConfig.particleCulling || particle == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            AxisAlignedBB box = particle.getBoundingBox();
            if (box == null || box.hasNaN()) return false;

            Entity camera = mc.getRenderViewEntity();
            if (camera == null) return false;

            double px = (box.minX + box.maxX) * 0.5D;
            double py = (box.minY + box.maxY) * 0.5D;
            double pz = (box.minZ + box.maxZ) * 0.5D;

            double dx = px - camX;
            double dy = py - camY;
            double dz = pz - camZ;
            double distance = AOCConfig.particleDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return true;
            }

            return !tileFrustum.isBoundingBoxInFrustum(box);
        } catch (Throwable ignored) {
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
            if (box == null) box = new AxisAlignedBB(pos);
            if (box.hasNaN() || box == TileEntity.INFINITE_EXTENT_AABB) return false;

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

            double dx = pos.getX() + 0.5D - camX;
            double dy = pos.getY() + 0.5D - camY;
            double dz = pos.getZ() + 0.5D - camZ;
            double distance = AOCConfig.tileEntityDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return true;
            }

            if (!AOCConfig.occlusionCulling) return false;

            boolean result = requestOcclusion(
                    mc.world,
                    TILE_OCCLUSION,
                    TILE_PENDING,
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

    private static <K> boolean requestOcclusion(
            World world,
            Map<K, CacheEntry> cache,
            Map<K, OcclusionTask<K>> pending,
            K key,
            AxisAlignedBB box,
            BlockPos targetBlock,
            double startX,
            double startY,
            double startZ) {

        if (currentWorld != world) {
            resetWorld(world);
        }

        CacheEntry cached = cache.get(key);
        if (cached != null
                && cached.isUsable(tick, startX, startY, startZ, box)) {
            return cached.occluded;
        }

        OcclusionTask<K> existing = pending.get(key);
        if (existing != null) {
            if (!existing.isCompatible(world, startX, startY, startZ, box)) {
                pending.remove(key);
                QUEUED.remove(key);
            } else {
                return false;
            }
        }

        if (!QUEUED.contains(key) && WORK_QUEUE.size() < 4096) {
            OcclusionTask<K> task = new OcclusionTask<K>(
                    world, key, box, targetBlock,
                    startX, startY, startZ, tick);
            pending.put(key, task);
            QUEUED.add(key);
            WORK_QUEUE.add(task);
        }

        return false;
    }

    public static void endClientTick() {
        tick++;
        updateCamera();

        processVisibilityWork();

        if (tick % 40L == 0L) {
            cleanupCaches();
        }
    }

    /**
     * Processes one sample per budget unit. A center sample is tested first;
     * only objects whose center is blocked consume more samples. This makes
     * visible objects cheap while retaining conservative multi-point culling.
     */
    private static void processVisibilityWork() {
        int budget = Math.max(0, AOCConfig.occlusionBudget);

        while (budget > 0 && !WORK_QUEUE.isEmpty()) {
            OcclusionTask<?> raw = WORK_QUEUE.poll();
            if (raw == null) break;

            QUEUED.remove(raw.key);

            if (raw.world != currentWorld || raw.isStale(tick)) {
                removePending(raw);
                continue;
            }

            RayResult ray = testSample(raw);
            budget--;

            if (ray == RayResult.BLOCKED) {
                raw.sampleIndex++;
                if (raw.sampleIndex >= raw.sampleCount()) {
                    putCache(raw, true);
                    removePending(raw);
                } else {
                    enqueueAgain(raw);
                }
            } else if (ray == RayResult.VISIBLE) {
                putCache(raw, false);
                removePending(raw);
            } else {
                // A failed/uncertain trace is fail-open. Do not cache it.
                removePending(raw);
            }
        }
    }

    private static RayResult testSample(OcclusionTask<?> task) {
        if (task.sampleIndex >= task.sampleCount()) return RayResult.VISIBLE;

        double[] sample = task.sample(task.sampleIndex);
        return rayBlockedByOpaqueBlock(
                task.world,
                task.startX, task.startY, task.startZ,
                sample[0], sample[1], sample[2],
                new BlockPos(task.startX, task.startY, task.startZ),
                task.targetBlock);
    }

    private static void enqueueAgain(OcclusionTask<?> task) {
        if (QUEUED.contains(task.key)) return;
        if (WORK_QUEUE.size() >= 4096) {
            removePending(task);
            return;
        }
        QUEUED.add(task.key);
        WORK_QUEUE.add(task);
    }

    private static void putCache(OcclusionTask<?> task, boolean occluded) {
        CacheEntry entry = new CacheEntry(
                occluded,
                tick,
                task.startX,
                task.startY,
                task.startZ,
                task.box);

        if (task.key instanceof Entity) {
            ENTITY_OCCLUSION.put((Entity) task.key, entry);
            if (occluded && !loggedEntityCull) {
                loggedEntityCull = true;
                System.out.println("[AOC] Entity block-occlusion culling is active.");
            }
        } else if (task.key instanceof BlockPos) {
            TILE_OCCLUSION.put((BlockPos) task.key, entry);
            if (occluded && !loggedTileCull) {
                loggedTileCull = true;
                System.out.println("[AOC] TileEntity block-occlusion culling is active.");
            }
        }
    }

    private static void removePending(OcclusionTask<?> task) {
        if (task.key instanceof Entity) {
            ENTITY_PENDING.remove((Entity) task.key);
        } else if (task.key instanceof BlockPos) {
            TILE_PENDING.remove((BlockPos) task.key);
        }
    }

    private static void resetWorld(World world) {
        currentWorld = world;
        ENTITY_OCCLUSION.clear();
        TILE_OCCLUSION.clear();
        ENTITY_PENDING.clear();
        TILE_PENDING.clear();
        WORK_QUEUE.clear();
        QUEUED.clear();
    }

    private static void cleanupCaches() {
        if (ENTITY_OCCLUSION.size() > 8192) {
            Iterator<Entity> it = ENTITY_OCCLUSION.keySet().iterator();
            while (it.hasNext() && ENTITY_OCCLUSION.size() > 4096) {
                Entity entity = it.next();
                if (entity == null || entity.isDead || entity.world != currentWorld) {
                    it.remove();
                }
            }
        }

        if (TILE_OCCLUSION.size() > 8192) {
            Iterator<BlockPos> it = TILE_OCCLUSION.keySet().iterator();
            while (it.hasNext() && TILE_OCCLUSION.size() > 4096) {
                BlockPos pos = it.next();
                if (pos == null || currentWorld == null
                        || !currentWorld.isBlockLoaded(pos)) {
                    it.remove();
                }
            }
        }

        if (WORK_QUEUE.size() > 4096) {
            WORK_QUEUE.clear();
            QUEUED.clear();
            ENTITY_PENDING.clear();
            TILE_PENDING.clear();
        }
    }

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

        RayTraceResult hit = world.rayTraceBlocks(
                new Vec3d(startX, startY, startZ),
                new Vec3d(endX, endY, endZ),
                false,
                true,
                false);

        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return RayResult.VISIBLE;
        }

        BlockPos hitPos = hit.getBlockPos();
        if (hitPos == null
                || cameraBlock.equals(hitPos)
                || (targetBlock != null && targetBlock.equals(hitPos))) {
            return RayResult.VISIBLE;
        }

        IBlockState state = world.getBlockState(hitPos);
        if (state == null || !state.isOpaqueCube()) {
            // A non-opaque first hit is treated as transparent. We do not
            // repeatedly ray trace through transparent blocks in the hot
            // path; uncertainty is fail-open.
            return RayResult.VISIBLE;
        }

        return RayResult.BLOCKED;
    }

    private enum RayResult {
        BLOCKED,
        VISIBLE
    }

    private static final class OcclusionTask<K> {
        final World world;
        final K key;
        final AxisAlignedBB box;
        final BlockPos targetBlock;
        final double startX;
        final double startY;
        final double startZ;
        final long createdTick;
        int sampleIndex;

        OcclusionTask(
                World world,
                K key,
                AxisAlignedBB box,
                BlockPos targetBlock,
                double startX,
                double startY,
                double startZ,
                long createdTick) {
            this.world = world;
            this.key = key;
            this.box = box;
            this.targetBlock = targetBlock;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.createdTick = createdTick;
        }

        int sampleCount() {
            return 5;
        }

        double[] sample(int index) {
            double ex = Math.min(0.05D,
                    Math.max(0.001D, (box.maxX - box.minX) * 0.25D));
            double ey = Math.min(0.05D,
                    Math.max(0.001D, (box.maxY - box.minY) * 0.25D));
            double ez = Math.min(0.05D,
                    Math.max(0.001D, (box.maxZ - box.minZ) * 0.25D));

            double cx = (box.minX + box.maxX) * 0.5D;
            double cy = (box.minY + box.maxY) * 0.5D;
            double cz = (box.minZ + box.maxZ) * 0.5D;

            switch (index) {
                case 0: return new double[] {cx, cy, cz};
                case 1: return new double[] {
                        box.minX + ex, box.minY + ey, box.minZ + ez};
                case 2: return new double[] {
                        box.maxX - ex, box.maxY - ey, box.minZ + ez};
                case 3: return new double[] {
                        box.minX + ex, box.maxY - ey, box.maxZ - ez};
                default: return new double[] {
                        box.maxX - ex, box.minY + ey, box.maxZ - ez};
            }
        }

        boolean isCompatible(
                World current,
                double x,
                double y,
                double z,
                AxisAlignedBB currentBox) {
            if (world != current) return false;

            double dx = x - startX;
            double dy = y - startY;
            double dz = z - startZ;

            return dx * dx + dy * dy + dz * dz <= 9.0D
                    && Math.abs(box.minX - currentBox.minX) < 0.05D
                    && Math.abs(box.minY - currentBox.minY) < 0.05D
                    && Math.abs(box.minZ - currentBox.minZ) < 0.05D
                    && Math.abs(box.maxX - currentBox.maxX) < 0.05D
                    && Math.abs(box.maxY - currentBox.maxY) < 0.05D
                    && Math.abs(box.maxZ - currentBox.maxZ) < 0.05D;
        }

        boolean isStale(long now) {
            return now - createdTick > 6L;
        }
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
