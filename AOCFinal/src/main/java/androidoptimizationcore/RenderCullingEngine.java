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

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative renderer-side culling.
 *
 * World/entity access and cache publication stay on the client thread; no
 * Minecraft world state is touched from a worker thread.
 */
public final class RenderCullingEngine {
    private static final Map<Long, CacheEntry> OCCLUSION = new ConcurrentHashMap<Long, CacheEntry>();
    private static final Map<Long, Boolean> PENDING = new ConcurrentHashMap<Long, Boolean>();
    private static final Queue<OcclusionRequest> REQUESTS = new ArrayDeque<OcclusionRequest>();

    private static volatile Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
    private static volatile double camX, camY, camZ;
    private static World currentWorld;
    private static long tick;

    private RenderCullingEngine() {}

    public static void updateCamera() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;

        float pt = mc.getRenderPartialTicks();
        camX = camera.prevPosX + (camera.posX - camera.prevPosX) * pt;
        camY = camera.prevPosY + (camera.posY - camera.prevPosY) * pt + camera.getEyeHeight();
        camZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * pt;

        Frustum f = new Frustum(ClippingHelperImpl.getInstance());
        f.setPosition(camX, camY, camZ);
        frustum = f;
    }

    public static boolean shouldCullEntity(Entity e) {
        if (!AOCConfig.entityCulling || e == null) return false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || e == mc.getRenderViewEntity()) return false;

        double r = AOCConfig.entityDistance;
        if (e.getDistanceSq(camX, camY, camZ) > r * r) return true;

        AxisAlignedBB box = e.getEntityBoundingBox();
        if (box == null || !frustum.isBoundingBoxInFrustum(box.grow(0.05D))) return true;

        return AOCConfig.occlusionCulling && requestOrUseOcclusion(mc.world, entityKey(e), box, null);
    }

    public static boolean shouldCullTileEntity(TileEntity te) {
        if (!AOCConfig.tileEntityCulling || te == null) return false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return false;

        BlockPos pos = te.getPos();
        AxisAlignedBB box = new AxisAlignedBB(pos);

        double dx = pos.getX() + .5D - camX;
        double dy = pos.getY() + .5D - camY;
        double dz = pos.getZ() + .5D - camZ;
        double r = AOCConfig.tileEntityDistance;

        if (dx * dx + dy * dy + dz * dz > r * r) return true;
        if (!frustum.isBoundingBoxInFrustum(box)) return true;

        return AOCConfig.occlusionCulling && requestOrUseOcclusion(mc.world, tileKey(te), box, pos);
    }

    private static long entityKey(Entity e) {
        return 0x4000000000000000L | (e.getEntityId() & 0x3FFFFFFFL);
    }

    private static long tileKey(TileEntity te) {
        BlockPos p = te.getPos();
        return ((long) p.getX() * 341873128712L)
                ^ ((long) p.getY() * 132897987541L)
                ^ ((long) p.getZ() * 42317861L);
    }

    private static boolean requestOrUseOcclusion(World world, long key, AxisAlignedBB box, BlockPos targetBlock) {
        if (currentWorld != world) {
            currentWorld = world;
            OCCLUSION.clear();
            PENDING.clear();
            synchronized (REQUESTS) {
                REQUESTS.clear();
            }
        }

        CacheEntry cached = OCCLUSION.get(key);
        if (cached != null && cached.isUsable(tick, camX, camY, camZ, box)) {
            return cached.occluded;
        }

        synchronized (REQUESTS) {
            if (!PENDING.containsKey(key)
                    && REQUESTS.size() < Math.max(1, AOCConfig.occlusionBudget * 2)) {
                PENDING.put(key, Boolean.TRUE);
                REQUESTS.offer(new OcclusionRequest(world, key, box, targetBlock, camX, camY, camZ, tick));
            }
        }

        // Fail-open until a fresh full blocking proof exists.
        return false;
    }

    public static void endClientTick() {
        tick++;

        // Camera is refreshed once per client tick instead of once per rendered object.
        updateCamera();

        int budget = AOCConfig.occlusionBudget;
        if (budget <= 0) {
            synchronized (REQUESTS) { REQUESTS.clear(); }
            PENDING.clear();
            return;
        }
        while (budget-- > 0) {
            OcclusionRequest r;
            synchronized (REQUESTS) {
                r = REQUESTS.poll();
            }
            if (r == null) break;

            boolean result = computeOcclusion(r.world, r.box, r.targetBlock, r.x, r.y, r.z);
            final long key = r.key;
            final CacheEntry value = new CacheEntry(
                    result, r.tick, r.x, r.y, r.z, r.box
            );

            OCCLUSION.put(key, value);
            PENDING.remove(key);
        }

        if (tick % 40L == 0L && OCCLUSION.size() > 8192) {
            OCCLUSION.clear();
        }
    }

    private static boolean computeOcclusion(World world, AxisAlignedBB b, BlockPos targetBlock, double x, double y, double z) {
        double[][] points = {
            {(b.minX + b.maxX) * .5D, (b.minY + b.maxY) * .5D, (b.minZ + b.maxZ) * .5D},
            {b.minX + .05D, b.minY + .05D, b.minZ + .05D},
            {b.maxX - .05D, b.minY + .05D, b.maxZ - .05D},
            {b.minX + .05D, b.maxY - .05D, b.maxZ - .05D}
        };

        int blocked = 0;
        for (double[] p : points) {
            RayTraceResult hit = world.rayTraceBlocks(
                    new Vec3d(x, y, z),
                    new Vec3d(p[0], p[1], p[2]),
                    false, true, false
            );
            if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
                if (targetBlock == null || !targetBlock.equals(hit.getBlockPos())) {
                    blocked++;
                }
            }
        }
        return blocked == points.length;
    }

    private static final class CacheEntry {
        final boolean occluded;
        final long tick;
        final double x, y, z;
        final double minX, minY, minZ, maxX, maxY, maxZ;

        CacheEntry(boolean occluded, long tick, double x, double y, double z, AxisAlignedBB box) {
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

        boolean isUsable(long now, double cx, double cy, double cz, AxisAlignedBB box) {
            if (now - tick > 4L) return false;
            double dx = cx - x, dy = cy - y, dz = cz - z;
            if (dx * dx + dy * dy + dz * dz > 2.25D) return false;
            return Math.abs(minX - box.minX) < .05D
                    && Math.abs(minY - box.minY) < .05D
                    && Math.abs(minZ - box.minZ) < .05D
                    && Math.abs(maxX - box.maxX) < .05D
                    && Math.abs(maxY - box.maxY) < .05D
                    && Math.abs(maxZ - box.maxZ) < .05D;
        }
    }

    private static final class OcclusionRequest {
        final World world;
        final long key;
        final AxisAlignedBB box;
        final BlockPos targetBlock;
        final double x, y, z;
        final long tick;

        OcclusionRequest(World world, long key, AxisAlignedBB box, BlockPos targetBlock,
                          double x, double y, double z, long tick) {
            this.world = world;
            this.key = key;
            this.box = box;
            this.targetBlock = targetBlock;
            this.x = x;
            this.y = y;
            this.z = z;
            this.tick = tick;
        }
    }
}
