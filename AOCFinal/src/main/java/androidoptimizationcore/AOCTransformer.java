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
    private static final String PM = "net.minecraft.client.particle.ParticleManager";

    private static final String CULL_ENGINE = "androidoptimizationcore/RenderCullingEngine";
    private static final String ENTITY_DESC =
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z";

    private static boolean loggedEntityHook;
    private static boolean loggedTileHook;
    private static boolean loggedParticleHook;
    private static boolean loggedEntityMiss;
    private static boolean loggedTileMiss;
    private static boolean loggedParticleMiss;

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
            if (PM.equals(name) || PM.equals(transformedName)) {
                return patchParticleManager(basicClass);
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

        /*
         * ORDER MATTERS.
         *
         * The vanilla IRETURNs of shouldRender() must be wrapped before AOC's
         * own early return is prepended. Inserting the cull hook first puts
         * its `ICONST_0; IRETURN` at the head of the method, and the wrapper
         * then lands on THAT return - which turns every cancelled occlusion
         * into "render anyway" (the wrapper calls the AOC distance test, which
         * is true for any entity inside the AOC range and inside the frustum).
         */
        if (!containsHook(target, "shouldAllowEntityWithinAocDistance")) {
            wrapEntityReturnForAocDistance(target);
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

    private byte[] patchParticleManager(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            if (!isParticleRenderMethodShape(m.desc)) continue;
            if (containsHook(m, "shouldCullParticle")) continue;
            patched += patchParticleRenderCalls(m);
        }

        if (patched == 0) {
            if (!loggedParticleMiss) {
                loggedParticleMiss = true;
                System.out.println("[AOC] WARNING: ParticleManager render targets were not found.");
            }
            return bytes;
        }

        if (!loggedParticleHook) {
            loggedParticleHook = true;
            System.out.println("[AOC] PATCHED ParticleManager render calls: " + patched);
        }

        return write(cn);
    }

    private byte[] patchTileDispatcher(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            if (!isTileRenderShape(m.desc)) continue;

            boolean changed = false;
            if (!containsHook(m, "shouldCullTileEntity")) {
                insertTileHook(m);
                changed = true;
            }
            if (isTileDistanceWrapperShape(m.desc)
                    && !containsHook(m, "getTileEntityMaxRenderDistanceSquared")) {
                patchTileDistanceLimit(m);
                changed = true;
            }

            if (changed) patched++;
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

    private static boolean isParticleRenderMethodShape(String desc) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(desc).getSort() != Type.VOID) return false;
        if (args.length != 2) return false;

        return args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.FLOAT;
    }

    private static int patchParticleRenderCalls(MethodNode method) {
        int patched = 0;
        int nextLocal = method.maxLocals;

        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; ) {
            AbstractInsnNode next = insn.getNext();

            if (insn instanceof MethodInsnNode
                    && isParticleRenderCall((MethodInsnNode) insn)) {
                MethodInsnNode call = (MethodInsnNode) insn;
                Type[] args = Type.getArgumentTypes(call.desc);

                int receiverLocal = nextLocal++;
                int[] argLocals = new int[args.length];

                for (int i = args.length - 1; i >= 0; i--) {
                    argLocals[i] = nextLocal;
                    nextLocal += args[i].getSize();
                }

                LabelNode render = new LabelNode();
                LabelNode skip = new LabelNode();
                InsnList patch = new InsnList();

                for (int i = args.length - 1; i >= 0; i--) {
                    patch.add(new VarInsnNode(
                            args[i].getOpcode(Opcodes.ISTORE),
                            argLocals[i]));
                }
                patch.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));

                patch.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
                patch.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        CULL_ENGINE,
                        "shouldCullParticle",
                        "(Lnet/minecraft/client/particle/Particle;)Z",
                        false
                ));
                patch.add(new JumpInsnNode(Opcodes.IFEQ, render));
                patch.add(new JumpInsnNode(Opcodes.GOTO, skip));

                patch.add(render);
                patch.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
                for (int i = 0; i < args.length; i++) {
                    patch.add(new VarInsnNode(
                            args[i].getOpcode(Opcodes.ILOAD),
                            argLocals[i]));
                }

                method.instructions.insertBefore(insn, patch);
                method.instructions.insert(insn, skip);
                patched++;
            }

            insn = next;
        }

        method.maxLocals = Math.max(method.maxLocals, nextLocal);
        return patched;
    }

    private static boolean isParticleRenderCall(MethodInsnNode call) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(call.desc).getSort() != Type.VOID) return false;
        if (args.length != 8) return false;
        if (args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT) {
            return false;
        }

        for (int i = 2; i < args.length; i++) {
            if (args[i].getSort() != Type.FLOAT) return false;
        }

        return true;
    }

    private static boolean isTileDistanceWrapperShape(String desc) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(desc);
        } catch (Throwable ignored) {
            return false;
        }

        return Type.getReturnType(desc).getSort() == Type.VOID
                && args.length == 3
                && args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.FLOAT
                && args[2].getSort() == Type.INT;
    }

    private static void patchTileDistanceLimit(MethodNode m) {
        Type[] methodArgs = Type.getArgumentTypes(m.desc);
        if (methodArgs.length == 0 || methodArgs[0].getSort() != Type.OBJECT) {
            return;
        }

        String tileEntityDesc = methodArgs[0].getDescriptor();
        boolean tileEntityIsLocal1 = methodArgs[0].getSize() == 1;

        for (AbstractInsnNode insn = m.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;

            MethodInsnNode call = (MethodInsnNode) insn;

            if (Type.getReturnType(call.desc).getSort() != Type.DOUBLE) continue;
            if (Type.getArgumentTypes(call.desc).length != 0) continue;

            if (!tileEntityDesc.equals("L" + call.owner + ";")) continue;

            if (!tileEntityIsLocal1) continue;
            AbstractInsnNode prev = previousReal(insn);
            if (!(prev instanceof VarInsnNode)
                    || prev.getOpcode() != Opcodes.ALOAD
                    || ((VarInsnNode) prev).var != 1) {
                continue;
            }

            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = CULL_ENGINE;
            call.name = "getTileEntityMaxRenderDistanceSquared";
            call.desc = "(Lnet/minecraft/tileentity/TileEntity;)D";
            call.itf = false;
            return;
        }
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode prev = insn.getPrevious();
        while (prev != null
                && (prev instanceof LabelNode
                    || prev instanceof LineNumberNode
                    || prev instanceof FrameNode)) {
            prev = prev.getPrevious();
        }
        return prev;
    }

    private static boolean isTileRenderShape(String desc) {
        Type[] args;
        try {
            args = Type.getArgumentTypes(desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(desc).getSort() != Type.VOID) return false;
        if (args.length < 3 || args.length > 7) return false;
        if (args[0].getSort() != Type.OBJECT) return false;

        // Render overload parameters after TileEntity are numeric: doubles,
        // floats and, for destroy-stage rendering, an int.
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

    private static void wrapEntityReturnForAocDistance(MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.IRETURN) continue;

            InsnList patch = new InsnList();
            LabelNode keepOriginal = new LabelNode();
            LabelNode noExtension = new LabelNode();
            LabelNode done = new LabelNode();

            patch.add(new InsnNode(Opcodes.DUP));
            patch.add(new JumpInsnNode(Opcodes.IFNE, keepOriginal));
            patch.add(new InsnNode(Opcodes.POP));

            patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
            patch.add(new VarInsnNode(Opcodes.ALOAD, 2));
            patch.add(new VarInsnNode(Opcodes.DLOAD, 3));
            patch.add(new VarInsnNode(Opcodes.DLOAD, 5));
            patch.add(new VarInsnNode(Opcodes.DLOAD, 7));

            patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CULL_ENGINE,
                    "shouldAllowEntityWithinAocDistance",
                    "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z",
                    false
            ));
            patch.add(new JumpInsnNode(Opcodes.IFEQ, noExtension));
            patch.add(new InsnNode(Opcodes.ICONST_1));
            patch.add(new JumpInsnNode(Opcodes.GOTO, done));

            patch.add(noExtension);
            patch.add(new InsnNode(Opcodes.ICONST_0));
            patch.add(new JumpInsnNode(Opcodes.GOTO, done));

            patch.add(keepOriginal);
            patch.add(new JumpInsnNode(Opcodes.GOTO, done));
            patch.add(done);

            m.instructions.insertBefore(insn, patch);
            return;
        }
    }

    private static void insertEntityShouldRenderHook(MethodNode m) {
        InsnList hook = new InsnList();
        LabelNode cull = new LabelNode();
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
        hook.add(new JumpInsnNode(Opcodes.IFNE, cull));

        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 3));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 5));
        hook.add(new VarInsnNode(Opcodes.DLOAD, 7));

        hook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CULL_ENGINE,
                "shouldCullVisualEffect",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z",
                false
        ));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));

        hook.add(cull);
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
