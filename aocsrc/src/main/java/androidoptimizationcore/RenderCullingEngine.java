package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
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
 * Render-thread-safe geometric culling plus bounded asynchronous result processing.
 * World access is NEVER performed by the worker thread; ray-trace requests are
 * sampled on the client thread and the cheap decision/caching work is offloaded.
 */
public final class RenderCullingEngine {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AOC-Culling");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Integer, Boolean> OCCLUSION = new ConcurrentHashMap<>();
    private static final Queue<OcclusionRequest> REQUESTS = new ArrayDeque<OcclusionRequest>();
    private static volatile Frustum frustum = new Frustum(ClippingHelperImpl.getInstance());
    private static volatile double camX, camY, camZ;
    private static volatile long lastCameraNanos;
    private static long tick;

    private RenderCullingEngine() {}

    public static void updateCamera() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.getRenderViewEntity();
        if (camera == null) return;
        Vec3d p = ActiveRenderInfo.getCameraPosition();
        if (p != null) {
            float pt = mc.getRenderPartialTicks();
            camX = camera.prevPosX + (camera.posX - camera.prevPosX) * pt + p.x;
            camY = camera.prevPosY + (camera.posY - camera.prevPosY) * pt + p.y;
            camZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * pt + p.z;
        } else {
            camX = camera.prevPosX + (camera.posX - camera.prevPosX) * mc.getRenderPartialTicks();
            camY = camera.prevPosY + (camera.posY - camera.prevPosY) * mc.getRenderPartialTicks() + camera.getEyeHeight();
            camZ = camera.prevPosZ + (camera.posZ - camera.prevPosZ) * mc.getRenderPartialTicks();
        }
        Frustum f = new Frustum(ClippingHelperImpl.getInstance());
        f.setPosition(camX, camY, camZ);
        frustum = f;
        lastCameraNanos = System.nanoTime();
    }

    private static void cameraIfStale() {
        if (System.nanoTime() - lastCameraNanos > 5_000_000L) updateCamera();
    }

    public static boolean shouldCullEntity(Entity e) {
        if (!AOCConfig.entityCulling || e == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || e == mc.getRenderViewEntity()) return false;
        cameraIfStale();
        if (e.getDistanceSq(camX, camY, camZ) > AOCConfig.entityDistance * (double) AOCConfig.entityDistance) return true;
        AxisAlignedBB box = e.getEntityBoundingBox();
        if (box != null && !frustum.isBoundingBoxInFrustum(box.grow(0.05D))) return true;
        if (AOCConfig.occlusionCulling) return requestOrUseOcclusion(mc.world, e.getEntityId(), box);
        return false;
    }

    public static boolean shouldCullTileEntity(TileEntity te) {
        if (!AOCConfig.tileEntityCulling || te == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return false;
        cameraIfStale();
        BlockPos pos = te.getPos();
        double dx = pos.getX() + .5D - camX, dy = pos.getY() + .5D - camY, dz = pos.getZ() + .5D - camZ;
        if (dx * dx + dy * dy + dz * dz > AOCConfig.tileEntityDistance * (double) AOCConfig.tileEntityDistance) return true;
        if (!frustum.isBoundingBoxInFrustum(new AxisAlignedBB(pos))) return true;
        return false;
    }

    private static boolean requestOrUseOcclusion(World world, int id, AxisAlignedBB box) {
        if (box == null) return false;
        Boolean cached = OCCLUSION.get(id);
        if (cached != null) return cached;
        synchronized (REQUESTS) {
            if (REQUESTS.size() < AOCConfig.occlusionBudget * 2) {
                REQUESTS.offer(new OcclusionRequest(world, id, box, camX, camY, camZ));
            }
        }
        // Conservative fallback: never hide an object before a positive proof exists.
        return false;
    }

    public static void endClientTick() {
        if (++tick % 2 == 0) updateCamera();
        int budget = AOCConfig.occlusionBudget;
        while (budget-- > 0) {
            OcclusionRequest r;
            synchronized (REQUESTS) { r = REQUESTS.poll(); }
            if (r == null) break;
            boolean occluded = computeOcclusionOnClientThread(r.world, r.box, r.x, r.y, r.z);
            final int id = r.id;
            final boolean result = occluded;
            WORKER.submit(() -> OCCLUSION.put(id, result));
        }
        if (tick % 40 == 0 && OCCLUSION.size() > 8192) OCCLUSION.clear();
    }

    private static boolean computeOcclusionOnClientThread(World world, AxisAlignedBB b, double x, double y, double z) {
        double[][] points = {
                {(b.minX+b.maxX)*.5D, (b.minY+b.maxY)*.5D, (b.minZ+b.maxZ)*.5D},
                {b.minX+.05D, b.minY+.05D, b.minZ+.05D},
                {b.maxX-.05D, b.minY+.05D, b.maxZ-.05D},
                {b.minX+.05D, b.maxY-.05D, b.maxZ-.05D}
        };
        int blocked = 0;
        for (double[] p : points) {
            RayTraceResult hit = world.rayTraceBlocks(new Vec3d(x,y,z), new Vec3d(p[0],p[1],p[2]), false, true, false);
            if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) blocked++;
        }
        return blocked == points.length;
    }

    private static final class OcclusionRequest {
        final World world; final int id; final AxisAlignedBB box; final double x,y,z;
        OcclusionRequest(World world, int id, AxisAlignedBB box, double x, double y, double z) {
            this.world=world; this.id=id; this.box=box; this.x=x; this.y=y; this.z=z;
        }
    }
}
