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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Conservative renderer-side culling. The worker thread never touches Minecraft World/Entity objects.
 * Occlusion queries are sampled and executed on the client thread; only cache writes are offloaded.
 */
public final class RenderCullingEngine {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AOC-Culling-Cache");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Long, Boolean> OCCLUSION = new ConcurrentHashMap<Long, Boolean>();
    private static final Queue<OcclusionRequest> REQUESTS = new ArrayDeque<OcclusionRequest>();
    private static volatile Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
    private static volatile double camX, camY, camZ;
    private static volatile long cameraFrame;
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
        cameraFrame++;
    }

    public static boolean shouldCullEntity(Entity e) {
        if (!AOCConfig.entityCulling || e == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || e == mc.getRenderViewEntity()) return false;
        updateCamera();
        double r = AOCConfig.entityDistance;
        if (e.getDistanceSq(camX, camY, camZ) > r * r) return true;
        AxisAlignedBB box = e.getEntityBoundingBox();
        if (box == null || !frustum.isBoundingBoxInFrustum(box.grow(0.05D))) return true;
        return AOCConfig.occlusionCulling && requestOrUseOcclusion(mc.world, entityKey(e), box);
    }

    public static boolean shouldCullTileEntity(TileEntity te) {
        if (!AOCConfig.tileEntityCulling || te == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return false;
        updateCamera();
        BlockPos pos = te.getPos();
        AxisAlignedBB box = new AxisAlignedBB(pos);
        double dx = pos.getX() + .5D - camX, dy = pos.getY() + .5D - camY, dz = pos.getZ() + .5D - camZ;
        double r = AOCConfig.tileEntityDistance;
        if (dx * dx + dy * dy + dz * dz > r * r) return true;
        if (!frustum.isBoundingBoxInFrustum(box)) return true;
        return AOCConfig.occlusionCulling && requestOrUseOcclusion(mc.world, tileKey(te), box);
    }

    private static long entityKey(Entity e) { return 0x4000000000000000L | (e.getEntityId() & 0x3FFFFFFFL); }
    private static long tileKey(TileEntity te) { BlockPos p = te.getPos(); return ((long)p.getX() * 341873128712L) ^ ((long)p.getY() * 132897987541L) ^ ((long)p.getZ() * 42317861L); }

    private static boolean requestOrUseOcclusion(World world, long key, AxisAlignedBB box) {
        Boolean cached = OCCLUSION.get(key);
        if (cached != null) return cached;
        synchronized (REQUESTS) {
            if (REQUESTS.size() < Math.max(1, AOCConfig.occlusionBudget * 2))
                REQUESTS.offer(new OcclusionRequest(world, key, box, camX, camY, camZ));
        }
        // Fail-open until a full blocking proof exists. Fast camera turns therefore never hide new objects.
        return false;
    }

    public static void endClientTick() {
        if ((++tick & 1L) == 0L) updateCamera();
        int budget = AOCConfig.occlusionBudget;
        while (budget-- > 0) {
            OcclusionRequest r;
            synchronized (REQUESTS) { r = REQUESTS.poll(); }
            if (r == null) break;
            boolean result = computeOcclusion(r.world, r.box, r.x, r.y, r.z);
            final long key = r.key;
            final boolean value = result;
            WORKER.submit(() -> OCCLUSION.put(key, value));
        }
        if (tick % 40L == 0L && OCCLUSION.size() > 8192) OCCLUSION.clear();
    }

    private static boolean computeOcclusion(World world, AxisAlignedBB b, double x, double y, double z) {
        double[][] points = {
            {(b.minX+b.maxX)*.5D, (b.minY+b.maxY)*.5D, (b.minZ+b.maxZ)*.5D},
            {b.minX+.05D, b.minY+.05D, b.minZ+.05D},
            {b.maxX-.05D, b.minY+.05D, b.maxZ-.05D},
            {b.minX+.05D, b.maxY-.05D, b.maxZ-.05D}
        };
        int blocked = 0;
        for (double[] p : points) {
            RayTraceResult hit = world.rayTraceBlocks(new Vec3d(x, y, z), new Vec3d(p[0], p[1], p[2]), false, true, false);
            if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) blocked++;
        }
        return blocked == points.length;
    }

    private static final class OcclusionRequest {
        final World world; final long key; final AxisAlignedBB box; final double x, y, z;
        OcclusionRequest(World world, long key, AxisAlignedBB box, double x, double y, double z) {
            this.world = world; this.key = key; this.box = box; this.x = x; this.y = y; this.z = z;
        }
    }
}
