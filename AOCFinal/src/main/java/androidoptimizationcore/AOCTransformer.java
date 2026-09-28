package androidoptimizationcore;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

public final class AOCTransformer implements IClassTransformer {
    private static final String RM = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String TE = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String TAS = "net.minecraft.client.renderer.texture.TextureAtlasSprite";

    private static boolean loggedEntityHook;
    private static boolean loggedTileHook;
    private static boolean loggedAnimationHook;
    private static boolean loggedEntityMiss;
    private static boolean loggedTileMiss;
    private static boolean loggedAnimationMiss;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (RM.equals(name) || RM.equals(transformedName)) {
                return patchRenderManager(basicClass);
            }
            if (TE.equals(name) || TE.equals(transformedName)) {
                return patchTileDispatcher(basicClass);
            }
            if (TAS.equals(name) || TAS.equals(transformedName)) {
                return patchTextureAtlasSprite(basicClass);
            }
        } catch (Throwable t) {
            System.out.println("[AOC] Transformer failure for " + name + " / " + transformedName + ": " + t);
            t.printStackTrace();
            return basicClass;
        }
        return basicClass;
    }

    private byte[] patchRenderManager(byte[] bytes) {
        ClassNode cn = read(bytes);
        boolean patchedShouldRender = false;

        for (MethodNode m : cn.methods) {
            if (!("shouldRender".equals(m.name) || "func_188390_a".equals(m.name))) continue;
            if (!"(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z".equals(m.desc)) continue;

            insertEntityShouldRenderHook(m);
            patchedShouldRender = true;
            break;
        }

        if (!patchedShouldRender) {
            if (!loggedEntityMiss) {
                loggedEntityMiss = true;
                System.out.println("[AOC] WARNING: RenderManager.shouldRender hook target was not found.");
            }
            return bytes;
        }

        if (!loggedEntityHook) {
            loggedEntityHook = true;
            System.out.println("[AOC] PATCHED RenderManager.shouldRender");
        }
        return write(cn);
    }

    private byte[] patchTileDispatcher(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            if (!("render".equals(m.name)
                    || "func_192854_a".equals(m.name)
                    || "func_192855_a".equals(m.name))) continue;
            if (!m.desc.startsWith("(Lnet/minecraft/tileentity/TileEntity;")) continue;
            if (!m.desc.endsWith(")V")) continue;

            insertTileHook(m);
            patched++;
        }

        if (patched == 0) {
            if (!loggedTileMiss) {
                loggedTileMiss = true;
                System.out.println("[AOC] WARNING: TileEntityRendererDispatcher render hook target was not found.");
            }
            return bytes;
        }

        if (!loggedTileHook) {
            loggedTileHook = true;
            System.out.println("[AOC] PATCHED TileEntityRendererDispatcher.render overloads: " + patched);
        }
        return write(cn);
    }

    private byte[] patchTextureAtlasSprite(byte[] bytes) {
        ClassNode cn = read(bytes);

        for (MethodNode m : cn.methods) {
            if (!("updateAnimation".equals(m.name) || "func_94219_l".equals(m.name))) continue;
            if (!"()V".equals(m.desc)) continue;

            insertAnimationHook(m);

            if (!loggedAnimationHook) {
                loggedAnimationHook = true;
                System.out.println("[AOC] PATCHED TextureAtlasSprite.updateAnimation");
            }
            return write(cn);
        }

        if (!loggedAnimationMiss) {
            loggedAnimationMiss = true;
            System.out.println("[AOC] WARNING: TextureAtlasSprite.updateAnimation hook target was not found.");
        }
        return bytes;
    }

    private static void insertEntityShouldRenderHook(MethodNode m) {
        InsnList hook = new InsnList();
        LabelNode pass = new LabelNode();

        // Entity argument is local variable 1.
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 5));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 7));

        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "androidoptimizationcore/RenderCullingEngine",
                "shouldCullEntity",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z",
                false
        ));

        hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        hook.add(new InsnNode(Opcodes.ICONST_0));
        hook.add(new InsnNode(Opcodes.IRETURN));
        hook.add(pass);

        m.instructions.insert(hook);
    }

    private static void insertTileHook(MethodNode m) {
        InsnList hook = new InsnList();
        LabelNode pass = new LabelNode();

        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "androidoptimizationcore/RenderCullingEngine",
                "shouldCullTileEntity",
                "(Lnet/minecraft/tileentity/TileEntity;)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(pass);

        m.instructions.insert(hook);
    }

    private static void insertAnimationHook(MethodNode m) {
        InsnList hook = new InsnList();
        LabelNode pass = new LabelNode();

        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "androidoptimizationcore/AnimatedTextureController",
                "shouldSkipAtlasUpdate",
                "(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(pass);

        m.instructions.insert(hook);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        // COMPUTE_FRAMES is safer when another coremod/OptiFine has already
        // modified the same method and changed its control-flow graph.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
