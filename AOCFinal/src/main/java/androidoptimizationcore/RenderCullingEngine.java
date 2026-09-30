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
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.EntityAreaEffectCloud;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
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
    private static final ThreadLocal<Boolean> PLAYER_NAME_RENDER =
            new ThreadLocal<Boolean>();

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

            /*
             * Player visibility is handled by the same generic entity pipeline.
             * The local player is always exempt because it is the camera/player
             * being controlled. Other players are ordinary client-side visual
             * entities here: their model can be hidden by Entity Distance or
             * conservative block occlusion, while their world state, movement,
             * inventory and server synchronization continue normally.
             */
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            if (entity == mc.getRenderViewEntity()) return false;

            boolean player = entity instanceof EntityPlayer;
            boolean droppedItem = entity instanceof EntityItem;

            if (player) {
                if (!AOCConfig.playerCulling) return false;
            } else if (droppedItem) {
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

            double distance = player
                    ? AOCConfig.playerDistance
                    : (droppedItem ? AOCConfig.itemDistance : AOCConfig.entityDistance);
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

            /*
             * Players use the same configurable Entity Distance as other
             * entities. This method is only a range-extension helper for
             * Vanilla's Render.shouldRender() result; it never changes player
             * simulation or state.
             */
            boolean player = entity instanceof EntityPlayer;
            boolean droppedItem = entity instanceof EntityItem;

            if (player) {
                if (!AOCConfig.playerCulling) return false;
            } else if (droppedItem) {
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

            double distance = player
                    ? AOCConfig.playerDistance
                    : (droppedItem ? AOCConfig.itemDistance : AOCConfig.entityDistance);
            double dx = entity.posX - renderCamX;
            double dy = entity.posY - renderCamY;
            double dz = entity.posZ - renderCamZ;

            return dx * dx + dy * dy + dz * dz <= distance * distance;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Marks the narrow RenderPlayer name-tag rendering scope. */
    public static void beginPlayerNameRender() {
        PLAYER_NAME_RENDER.set(Boolean.valueOf(AOCConfig.playerNameOpacityEnabled));
    }

    /** Clears the player name-tag rendering scope even if the option is disabled. */
    public static void endPlayerNameRender() {
        PLAYER_NAME_RENDER.remove();
    }

    /**
     * Changes the alpha component of the vanilla packed text color only while
     * a player name tag is being rendered. Vanilla opacity is 1.0F.
     */
    public static int adjustPlayerNameColor(int color) {
        try {
            if (!AOCConfig.playerNameOpacityEnabled
                    || !Boolean.TRUE.equals(PLAYER_NAME_RENDER.get())) {
                return color;
            }
            int alpha = Math.max(0, Math.min(255,
                    Math.round(AOCConfig.playerNameOpacity * 255.0F)));
            return (color & 0x00FFFFFF) | (alpha << 24);
        } catch (Throwable ignored) {
            return color;
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

            /*
             * IMPORTANT:
             * This method is called only at the particle draw call.
             * It must NEVER touch ParticleManager queues, updateEffects(),
             * particle lifetime or the particle's state.
             *
             * For this first reliable particle implementation we use only
             * distance. Camera/frustum rejection is deliberately omitted so
             * that turning the camera away cannot make a particle appear to             * have been removed. The particle keeps updating normally and is
             * rendered again as soon as it is inside the configured range.
             */
            Entity camera = mc.getRenderViewEntity();
            if (camera == null) return false;

            double px = (box.minX + box.maxX) * 0.5D;
            double py = (box.minY + box.maxY) * 0.5D;
            double pz = (box.minZ + box.maxZ) * 0.5D;

            double dx = px - camera.posX;
            double dy = py - camera.posY;
            double dz = pz - camera.posZ;

            double distance = AOCConfig.particleDistance;
            return dx * dx + dy * dy + dz * dz > distance * distance;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Expands the dispatcher's vanilla TileEntity range only when AOC is
     * enabled. A custom TileEntity renderer may provide a larger vanilla
     * range; that larger value is always preserved.
     */
    /**
     * Returns the TileEntity render-distance limit used by the dispatcher.
     *
     * AOC keeps the vanilla/custom renderer limit as a lower bound so a mod
     * with a deliberately larger range is still allowed to reach the AOC
     * distance hook. The actual configured maximum is enforced by
     * shouldCullTileEntity(), which runs before the renderer call.
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

    /**
     * TileEntity rendering category is discovered from the actual runtime
     * renderer, never from a block ID, registry name or mod ID.
     *
     * NORMAL_TESR  = ordinary TileEntitySpecialRenderer
     * FAST_TESR    = TileEntity reports that it uses the batched FastTESR path
     * GLOBAL_TESR  = renderer declares itself global
     * NO_RENDERER  = no registered special renderer; fail open for compatibility
     */
    private enum TileEntityRenderCategory {
        NORMAL_TESR,
        FAST_TESR,
        GLOBAL_TESR,
        NO_RENDERER
    }

    private static TileEntityRenderCategory getTileEntityRenderCategory(
            TileEntity tileEntity,
            TileEntitySpecialRenderer renderer) {
        if (tileEntity == null) return TileEntityRenderCategory.NO_RENDERER;

        try {
            if (renderer != null && renderer.isGlobalRenderer(tileEntity)) {
                return TileEntityRenderCategory.GLOBAL_TESR;
            }

            if (tileEntity.hasFastRenderer()) {
                return TileEntityRenderCategory.FAST_TESR;
            }

            if (renderer != null) {
                return TileEntityRenderCategory.NORMAL_TESR;
            }
        } catch (Throwable ignored) {
            // Unknown/custom renderer behavior: keep the TileEntity visible.
        }

        return TileEntityRenderCategory.NO_RENDERER;
    }

    /**
     * Cheap TileEntity distance gate shared by all renderer categories.
     *
     * The category decides which rendering path the TE belongs to, but the
     * user-facing TileEntity Distance remains one setting. This is deliberate:
     * modded TileEntities are classified by their real renderer path rather
     * than by hard-coded vanilla classes, so the same code works with mods.
     */
    public static boolean shouldCullTileEntity(TileEntity tileEntity) {
        try {
            if (!AOCConfig.tileEntityCulling || tileEntity == null) return false;

            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.world == null) return false;

            BlockPos pos = tileEntity.getPos();
            if (pos == null) return false;

            TileEntitySpecialRenderer renderer =
                    TileEntityRendererDispatcher.instance.getRenderer(tileEntity);
            TileEntityRenderCategory category =
                    getTileEntityRenderCategory(tileEntity, renderer);

            // A TileEntity without a TESR is not ours to hide here. Its block
            // model is rendered by the chunk pipeline, so failing open avoids
            // changing normal block rendering for custom mods.
            if (category == TileEntityRenderCategory.NO_RENDERER) {
                return false;
            }

            AxisAlignedBB box = tileEntity.getRenderBoundingBox();
            if (box == null) box = new AxisAlignedBB(pos);

            // Infinite/global renderers intentionally stay fail-open. They can
            // represent effects whose visible geometry extends far beyond the
            // TileEntity position (for example beams). Finite global renderers
            // still use the same configured distance below.
            if (box.hasNaN()) return false;
            if (box == TileEntity.INFINITE_EXTENT_AABB) {
                if (category == TileEntityRenderCategory.GLOBAL_TESR) return false;
                return false;
            }

            if (box.maxX - box.minX > 64.0D
                    || box.maxY - box.minY > 64.0D
                    || box.maxZ - box.minZ > 64.0D) {
                return false;
            }

            double dx = pos.getX() + 0.5D - camX;
            double dy = pos.getY() + 0.5D - camY;
            double dz = pos.getZ() + 0.5D - camZ;
            double distance = AOCConfig.tileEntityDistance;

            // This is the primary TileEntity Distance gate. It is performed
            // before any expensive occlusion work and therefore works equally
            // for normal TESR, FastTESR and compatible modded renderers.
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
            // Any unexpected mod renderer behavior must never break rendering.
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
                /*
                 * The old task may still be physically present in WORK_QUEUE.
                 * Remove that exact task before replacing it; otherwise the old
                 * task can later remove the new pending entry for the same key.
                 */
                pending.remove(key);
                WORK_QUEUE.remove(existing);
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

            /*
             * A task can be superseded when the camera moves. Only the current
             * task for a key is allowed to touch pending/queued state.
             */
            if (!isCurrentPendingTask(raw)) {
                continue;
            }

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

    private static boolean isCurrentPendingTask(OcclusionTask<?> task) {
        if (task == null || task.key == null) return false;

        if (task.key instanceof Entity) {
            return ENTITY_PENDING.get((Entity) task.key) == task;        }

        if (task.key instanceof BlockPos) {
            return TILE_PENDING.get((BlockPos) task.key) == task;
        }

        return false;
    }

    private static void removePending(OcclusionTask<?> task) {
        if (task == null || task.key == null) return;

        if (task.key instanceof Entity) {
            Entity entity = (Entity) task.key;
            if (ENTITY_PENDING.get(entity) == task) {
                ENTITY_PENDING.remove(entity);
            }
        } else if (task.key instanceof BlockPos) {
            BlockPos pos = (BlockPos) task.key;
            if (TILE_PENDING.get(pos) == task) {
                TILE_PENDING.remove(pos);
            }
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

    /**
     * Category-based block occlusion.
     *
     * This intentionally does NOT use World.rayTraceBlocks(). Instead AOC
     * walks the voxel cells between the camera and the target and asks only
     * one question for each cell: is this block in the SOLID occluder
     * category? Transparent/partial blocks are the PASS_THROUGH category.
     *
     * This keeps modded blocks generic: no registry IDs, mod names or block
     * blacklists are required. A wall made from a solid opaque cube blocks;
     * glass, panes, fences, slabs, stairs and other partial/transparent
     * geometry keep the path open.
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
        if (world == null) return RayResult.VISIBLE;

        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        if (lengthSq <= 1.0E-8D) return RayResult.VISIBLE;

        int x = floorToInt(startX);
        int y = floorToInt(startY);
        int z = floorToInt(startZ);
        int endCellX = floorToInt(endX);
        int endCellY = floorToInt(endY);
        int endCellZ = floorToInt(endZ);

        int stepX = dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        int stepY = dy > 0.0D ? 1 : (dy < 0.0D ? -1 : 0);
        int stepZ = dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);

        double nextBoundaryX = stepX > 0 ? x + 1.0D : x;
        double nextBoundaryY = stepY > 0 ? y + 1.0D : y;
        double nextBoundaryZ = stepZ > 0 ? z + 1.0D : z;

        double tMaxX = stepX == 0
                ? Double.POSITIVE_INFINITY
                : (nextBoundaryX - startX) / dx;
        double tMaxY = stepY == 0
                ? Double.POSITIVE_INFINITY
                : (nextBoundaryY - startY) / dy;
        double tMaxZ = stepZ == 0
                ? Double.POSITIVE_INFINITY
                : (nextBoundaryZ - startZ) / dz;

        // Prevent a pathological modded coordinate from becoming an endless
        // traversal. Normal Minecraft entity distances are far below this.
        int maxSteps = Math.min(2048,
                4 + (int) Math.ceil(Math.sqrt(lengthSq) * 3.0D));

        for (int step = 0; step < maxSteps; step++) {
            if (x == endCellX && y == endCellY && z == endCellZ) {
                return RayResult.VISIBLE;
            }

            BlockPos pos = new BlockPos(x, y, z);
            if (!pos.equals(cameraBlock)
                    && (targetBlock == null || !pos.equals(targetBlock))) {
                IBlockState state = world.getBlockState(pos);
                if (isSolidOccluderCategory(world, pos, state)) {
                    return RayResult.BLOCKED;
                }
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
        }

        // Uncertain traversal is always fail-open.
        return RayResult.VISIBLE;
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    /**
     * SOLID category used by entity/TileEntity occlusion. The category is
     * derived from the block state, so it works with vanilla and modded
     * blocks without IDs or hard-coded mod lists.
     */
    private static boolean isSolidOccluderCategory(
            World world,
            BlockPos pos,
            IBlockState state) {
        if (state == null || world == null || pos == null) return false;

        try {
            if (state.getBlock().isAir(state, world, pos)) return false;

            // Anything that is not a complete cube stays in PASS_THROUGH.
            if (!state.isFullCube()) return false;

            /*
             * Minecraft's normal-cube classification is the state/category
             * signal we want here: full solid cubes block visibility, while
             * glass, panes, fences, slabs, stairs and other partial or
             * transparent states remain PASS_THROUGH. No registry IDs or
             * mod-specific lists are used.
             */
            return state.isNormalCube();
        } catch (Throwable ignored) {
            // Unknown custom geometry: fail open for compatibility.
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
             * Center first. If the center is visible, the object is visible.
             * If the center is blocked, test four additional points before
             * hiding it. This prevents a large/custom render box from being
             * culled when only its center happens to be behind a solid block.
             */
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
            long maxAge = occluded ? 6L : 20L;
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