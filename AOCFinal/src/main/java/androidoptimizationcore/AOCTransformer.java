package androidoptimizationcore;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

public final class AOCTransformer implements IClassTransformer {
    private static final String RM = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String TE = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";

    private static final String CULL_ENGINE = "androidoptimizationcore/RenderCullingEngine";
    private static final String ENTITY_DESC =
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z";

    private static boolean loggedEntityHook;
    private static boolean loggedTileHook;
    private static boolean loggedEntityMiss;
    private static boolean loggedTileMiss;

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
        } catch (Throwable t) {
            System.out.println("[AOC] Transformer failure for " + name + " / " + transformedName + ": " + t);
            t.printStackTrace();
            return basicClass;
        }

        return basicClass;
    }

    private byte[] patchRenderManager(byte[] bytes) {
        ClassNode cn = read(bytes);
        MethodNode target = null;

        for (MethodNode m : cn.methods) {
            if (ENTITY_DESC.equals(m.desc)) {
                target = m;
                break;
            }

            // Production 1.12.2 bytecode is obfuscated, so the descriptor can
            // also contain obfuscated class names. Match the method by shape:
            // (object, object, double, double, double) -> boolean.
            if (isEntityShouldRenderShape(m.desc)) {
                target = m;
                break;
            }
        }

        if (target == null) {
            if (!loggedEntityMiss) {
                loggedEntityMiss = true;
                System.out.println("[AOC] WARNING: RenderManager.shouldRender target was not found.");
            }
            return bytes;
        }

        if (!containsHook(target, "shouldCullEntity")) {
            insertEntityShouldRenderHook(target);
        }

        if (!loggedEntityHook) {
            loggedEntityHook = true;
            System.out.println("[AOC] PATCHED RenderManager.shouldRender (descriptor/shape match)");
        }

        return write(cn);
    }

    private byte[] patchTileDispatcher(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            if (!isTileRenderShape(m.desc)) continue;
            if (containsHook(m, "shouldCullTileEntity")) continue;

            insertTileHook(m);
            patched++;
        }

        if (patched == 0) {
            if (!loggedTileMiss) {
                loggedTileMiss = true;
                System.out.println("[AOC] WARNING: TileEntityRendererDispatcher render targets were not found.");
            }
            return bytes;
        }

        if (!loggedTileHook) {
            loggedTileHook = true;
            System.out.println("[AOC] PATCHED TileEntityRendererDispatcher render overloads: " + patched);
        }

        return write(cn);
    }

    private static boolean isEntityShouldRenderShape(String desc) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(desc).getSort() != Type.BOOLEAN) return false;
        if (args.length != 5) return false;

        return args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.OBJECT
                && args[2].getSort() == Type.DOUBLE
                && args[3].getSort() == Type.DOUBLE
                && args[4].getSort() == Type.DOUBLE;
    }

    private static boolean isTileRenderShape(String desc) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(desc).getSort() != Type.VOID) return false;
        if (args.length < 4 || args.length > 7) return false;
        if (args[0].getSort() != Type.OBJECT) return false;

        // All parameters after TileEntity in the four render overloads are
        // primitives: doubles/floats and, for destroy stage, an int.
        for (int i = 1; i < args.length; i++) {
            int sort = args[i].getSort();
            if (sort != Type.DOUBLE && sort != Type.FLOAT && sort != Type.INT) {
                return false;
            }
        }

        return true;
    }

    private static boolean containsHook(MethodNode method, String methodName) {
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {

            if (!(insn instanceof MethodInsnNode)) continue;

            MethodInsnNode call = (MethodInsnNode) insn;
            if (Opcodes.INVOKESTATIC == call.getOpcode()
                    && CULL_ENGINE.equals(call.owner)
                    && methodName.equals(call.name)) {
                return true;
            }
        }

        return false;
    }

    private static void insertEntityShouldRenderHook(MethodNode m) {
        InsnList hook = new InsnList();
        LabelNode pass = new LabelNode();

        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 5));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 7));

        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CULL_ENGINE,
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
                CULL_ENGINE,
                "shouldCullTileEntity",
                "(Lnet/minecraft/tileentity/TileEntity;)Z",
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
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
