package androidoptimizationcore;

import androidoptimizationcore.api.AOCOptimizationRuntime;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.EntityAreaEffectCloud;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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

    private static double camX;
    private static double camY;
    private static double camZ;
    private static float camYaw;
    private static float camPitch;
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
        camYaw = camera.prevRotationYaw + (camera.rotationYaw - camera.prevRotationYaw) * pt;
        camPitch = camera.prevRotationPitch + (camera.rotationPitch - camera.prevRotationPitch) * pt;
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
            if (entity == null) {
                return false;
            }

            boolean droppedItem = entity instanceof EntityItem;
            if (droppedItem) {
                if (!AOCConfig.itemCulling) return false;
            } else if (!AOCConfig.entityCulling) {
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

            double distance = droppedItem
                    ? AOCConfig.itemDistance : AOCConfig.entityDistance;
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
     * Keeps Vanilla's normal Render.shouldRender decision intact, but when
     * Vanilla rejects an entity only because its normal render-distance rule
     * is smaller than the AOC-configured range, AOC may extend visibility up
     * to Entity Distance / Dropped Item Distance.
     */
    public static boolean shouldAllowEntityWithinAocDistance(
            Entity entity,
            ICamera camera,
            double renderCamX,
            double renderCamY,
            double renderCamZ) {
        try {
            if (entity == null) return false;

            boolean droppedItem = entity instanceof EntityItem;
            if (droppedItem) {
                if (!AOCConfig.itemCulling) return false;
            } else if (!AOCConfig.entityCulling) {
                return false;
            }

            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box == null || box.hasNaN()) return false;

            if (camera != null && !entity.ignoreFrustumCheck
                    && !camera.isBoundingBoxInFrustum(box.grow(0.05D))) {
                return false;
            }

            double distance = droppedItem
                    ? AOCConfig.itemDistance : AOCConfig.entityDistance;
            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;

            return dx * dx + dy * dy + dz * dz <= distance * distance;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * TileEntities do not receive ICamera in their dispatcher API. AOC therefore
     * keeps this path independent of the renderer's frustum state and uses only
     * the configured distance plus conservative solid-block occlusion.
     */
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

            double px = (box.minX + box.maxX) * 0.5D;
            double py = (box.minY + box.maxY) * 0.5D;
            double pz = (box.minZ + box.maxZ) * 0.5D;

            /*
             * ParticleManager does not expose the active ICamera to this hook.
             * Use the actual render-view entity look vector for the one frustum
             * case that matters here: a particle that is completely behind the
             * camera. The half-diagonal margin makes this fail-open for large
             * particle bounds that cross the camera plane.
             */
            double dx = px - camX;
            double dy = py - camY;
            double dz = pz - camZ;

            Entity camera = mc.getRenderViewEntity();
            if (camera == null) return false;

            Vec3d look = camera.getLook(mc.getRenderPartialTicks());
            if (look == null) return false;

            /*
             * Use Minecraft's own interpolated camera look vector instead of
             * rebuilding it from yaw/pitch. This follows the actual render
             * camera direction and remains valid for custom camera entities.
             */
            double halfDiagonal = 0.5D * Math.sqrt(
                    (box.maxX - box.minX) * (box.maxX - box.minX)
                            + (box.maxY - box.minY) * (box.maxY - box.minY)
                            + (box.maxZ - box.minZ) * (box.maxZ - box.minZ));

            double forwardProjection =
                    dx * look.x
                            + dy * look.y
                            + dz * look.z;

            if (forwardProjection + halfDiagonal < -0.05D) {
                return true;
            }

            double distance = AOCConfig.particleDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return true;
            }

            /*
             * Particle wall occlusion is intentionally deferred to the
             * particle stage. Particles do not enter the entity/TileEntity
             * wall-occlusion queue or consume its ray budget.
             */
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }


    /**
     * Expands the dispatcher's vanilla TileEntity range only when AOC is
     * enabled. A custom TileEntity renderer may provide a larger vanilla
     * range; that larger value is always preserved.
     */
    public static double getTileEntityMaxRenderDistanceSquared(
            TileEntity tileEntity) {
        try {
            if (tileEntity == null || !AOCConfig.tileEntityCulling) {
                return tileEntity == null
                        ? 0.0D
                        : tileEntity.getMaxRenderDistanceSquared();
            }

            double vanilla = tileEntity.getMaxRenderDistanceSquared();
            double aoc = (double) AOCConfig.tileEntityDistance
                    * (double) AOCConfig.tileEntityDistance;
            return Math.max(vanilla, aoc);
        } catch (Throwable ignored) {
            return 4096.0D;
        }
    }

    public static boolean shouldCullTileEntity(TileEntity tileEntity) {
        try {
            if (!AOCConfig.tileEntityCulling || tileEntity == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            BlockPos pos = tileEntity.getPos();
            if (pos == null) return false;

            TileEntitySpecialRenderer renderer =
                    TileEntityRendererDispatcher.instance.getRenderer(tileEntity);
            if (renderer != null && renderer.isGlobalRenderer(tileEntity)) {
                return false;
            }

            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            if (box == null) box = new AxisAlignedBB(pos);
            if (box.hasNaN() || box == TileEntity.INFINITE_EXTENT_AABB) return false;

            if (box.maxX - box.minX > 64.0D
                    || box.maxY - box.minY > 64.0D
                    || box.maxZ - box.minZ > 64.0D) {
                return false;
            }

            double dx = pos.getX() + 0.5D - camX;
            double dy = pos.getY() + 0.5D - camY;
            double dz = pos.getZ() + 0.5D - camZ;
            double distance = AOCConfig.tileEntityDistance;

            if (dx * dx + dy * dy + dz * dz > distance * distance) {
                return true;
            }

            if (!AOCConfig.occlusionCulling) return false;

            return requestOcclusion(
                    mc.world,
                    TILE_OCCLUSION,
                    TILE_PENDING,
                    pos,
                    box,
                    pos,
                    camX,
                    camY,
                    camZ);
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

        AOCOptimizationRuntime.get().resetBudget();
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
        AOCOptimizationRuntime runtime = AOCOptimizationRuntime.get();

        while (runtime.getRemainingVisualChecks() > 0
                && !WORK_QUEUE.isEmpty()) {
            OcclusionTask<?> raw = WORK_QUEUE.poll();
            if (raw == null) break;

            QUEUED.remove(raw.key);

            if (raw.world != currentWorld || raw.isStale(tick)) {
                removePending(raw);
                continue;
            }

            RayResult ray = testSample(raw);

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

        AOCOptimizationRuntime runtime = AOCOptimizationRuntime.get();
        if (!runtime.tryConsumeVisualCheck()) return RayResult.VISIBLE;

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

        double sx = startX;
        double sy = startY;
        double sz = startZ;

        /*
         * Category-based visibility: transparent and non-full blocks are
         * see-through. We continue the same ray past those blocks instead of
         * maintaining a whitelist of registry IDs. The hard cap keeps chains
         * of panes/leaves/custom transparent geometry cheap.
         */
        for (int pass = 0; pass < 8; pass++) {
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
            if (hitPos == null
                    || cameraBlock.equals(hitPos)
                    || (targetBlock != null && targetBlock.equals(hitPos))) {
                return RayResult.VISIBLE;
            }

            IBlockState state = world.getBlockState(hitPos);
            if (isOcclusionBlockingState(world, hitPos, state)) {
                return RayResult.BLOCKED;
            }

            Vec3d direction = new Vec3d(endX - sx, endY - sy, endZ - sz);
            double length = Math.sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
            if (length <= 1.0E-4D) return RayResult.VISIBLE;

            /*
             * Move beyond the current non-blocking intersection. This lets
             * the ray see through glass, panes, fences, slabs and similar
             * geometry until it reaches a genuinely blocking full cube.
             */
            double step = Math.min(0.02D, length * 0.25D);
            Vec3d next = hit.hitVec.add(direction.normalize().scale(step));
            sx = next.x;
            sy = next.y;
            sz = next.z;
        }

        // Too many transparent/partial intersections = uncertainty.
        // Never hide an object on uncertainty.
        return RayResult.VISIBLE;
    }

    /**
     * Classifies a block using vanilla block-state geometry/material data,
     * rather than a hard-coded registry/ID list. This keeps AOC generic for
     * modded blocks: full opaque cubes can block a visibility ray; partial,
     * transparent or otherwise uncertain shapes keep the view open.
     */
    private static boolean isOcclusionBlockingState(
            World world,
            BlockPos pos,
            IBlockState state) {
        if (state == null || world == null || pos == null) return false;

        try {
            // Air is explicitly see-through.
            if (state.getBlock().isAir(state, world, pos)) return false;

            // Slabs, fences, panes, trapdoors, stairs and other partial
            // geometry belong to the see-through category for this stage.
            if (!state.isFullCube()) return false;

            /*
             * Full opaque cubes are the normal wall category. The material
             * fallback also catches full blocks whose implementation reports
             * opacity through the material rather than isOpaqueCube().
             */
            return state.isOpaqueCube()
                    || (state.getMaterial() != null
                    && state.getMaterial().isOpaque());
        } catch (Throwable ignored) {
            // Unknown/custom block state: fail open for mod compatibility.
            return false;
        }
    }

    private static float angleChanged(float a, float b) {
        float delta = (a - b) % 360.0F;
        if (delta > 180.0F) delta -= 360.0F;
        if (delta < -180.0F) delta += 360.0F;
        return Math.abs(delta);
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
        final float yaw;
        final float pitch;
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
            this.yaw = camYaw;
            this.pitch = camPitch;
        }

        int sampleCount() {
            /*
             * Stage 1 deliberately uses one center ray. This keeps wall
             * occlusion cheap for large groups of mobs/items while avoiding
             * the multi-ray cost that made the previous scheduler too slow.
             */
            return 1;
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

            if (dx * dx + dy * dy + dz * dz > 9.0D) return false;
            if (angleChanged(yaw, camYaw) > 6.0F
                    || Math.abs(pitch - camPitch) > 6.0F) return false;

            return Math.abs(box.minX - currentBox.minX) < 0.05D
                    && Math.abs(box.minY - currentBox.minY) < 0.05D
                    && Math.abs(box.minZ - currentBox.minZ) < 0.05D
                    && Math.abs(box.maxX - currentBox.maxX) < 0.05D
                    && Math.abs(box.maxY - currentBox.maxY) < 0.05D
                    && Math.abs(box.maxZ - currentBox.maxZ) < 0.05D;
        }

        boolean isStale(long now) {
            /*
             * A large modded scene may need several ticks to pass through the
             * bounded ray budget. Do not expire a queued visibility task
             * before it gets a chance to finish.
             */
            return now - createdTick > 128L;
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
        final float yaw;
        final float pitch;

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
            this.yaw = camYaw;
            this.pitch = camPitch;
        }

        boolean isUsable(
                long now,
                double cameraX,
                double cameraY,
                double cameraZ,
                AxisAlignedBB box) {
            long maxAge = occluded ? 1L : 20L;
            if (now - tick > maxAge) return false;

            double dx = cameraX - x;
            double dy = cameraY - y;
            double dz = cameraZ - z;

            if (dx * dx + dy * dy + dz * dz > 2.25D) {
                return false;
            }
            if (angleChanged(yaw, camYaw) > 6.0F
                    || Math.abs(pitch - camPitch) > 6.0F) {
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
