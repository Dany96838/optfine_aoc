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
    private static final String RG = "net.minecraft.client.renderer.RenderGlobal";
    private static final String RP = "net.minecraft.client.renderer.entity.RenderPlayer";
    private static final String FR = "net.minecraft.client.gui.FontRenderer";

    private static final String CULL_ENGINE = "androidoptimizationcore/RenderCullingEngine";
    private static final String ENTITY_DESC =
            "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z";

    private static boolean loggedEntityHook;
    private static boolean loggedTileHook;
    private static boolean loggedParticleHook;
    private static boolean loggedEntityMiss;
    private static boolean loggedTileMiss;
    private static boolean loggedParticleMiss;
    private static boolean loggedRenderGlobalTileHook;
    private static boolean loggedRenderGlobalTileMiss;

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
            if (RG.equals(name) || RG.equals(transformedName)) {
                return patchRenderGlobalTileCalls(basicClass);
            }
            if (RP.equals(name) || RP.equals(transformedName)) {
                return patchRenderPlayerName(basicClass);
            }
            if (FR.equals(name) || FR.equals(transformedName)) {
                return patchFontRenderer(basicClass);
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

    /*
     * ------------------------------------------------------------------
     * ParticleManager: APENAS portao de render. Nada de remocao.
     * ------------------------------------------------------------------
     *
     * Este patcher instala shouldCullParticle em EXATAMENTE dois metodos, e
     * somente em volta da unica chamada de desenho de cada um:
     *
     *   renderParticles(Entity, float)      -> loop de c[a][b], um draw
     *   renderLitParticles(Entity, float)   -> loop de c[a][b], um draw
     *
     * Nada mais e tocado:
     *   - as listas c[a][b] nunca sao alteradas nem lidas aqui;
     *   - addEffect / a(int, Particle) / a(Particle) / a(Queue) / b(Particle)
     *     nao sao patchados: spawn continua identico;
     *   - updateEffects / updateEffectLayer e o tick de Particle nao sao
     *     patchados: movimento, gravidade, lifetime e animacao continuam
     *     identicos;
     *   - nenhum campo e nenhum caminho de remocao (removeAll/removeFirst)
     *     e tocado.
     *
     * Um "true" aqui significa apenas "nao emita o draw call deste frame".
     * A particula continua existindo, continua atualizando e volta a ser
     * desenhada assim que entrar em distancia e ficar a frente da camera.
     */
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
        if (call == null || call.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;

        /*
         * The two ParticleManager render methods call Particle.renderParticle.
         * The owner and descriptor names vary with runtime mappings, so the
         * stable signature is matched here. This matcher is only reached from
         * the exact renderParticles/renderLitParticles methods, never from
         * spawn/update code.
         */
        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (Throwable ignored) {
            return false;
        }

        if (Type.getReturnType(call.desc).getSort() != Type.VOID
                || args.length != 8
                || args[0].getSort() != Type.OBJECT
                || args[1].getSort() != Type.OBJECT) {
            return false;
        }

        for (int i = 2; i < args.length; i++) {
            if (args[i].getSort() != Type.FLOAT) return false;
        }

        return "renderParticle".equals(call.name) || "a".equals(call.name);
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

    /*
     * Rewrites the dispatcher's per-tileEntity range test
     *
     *     te.getDistanceSq(px, py, pz) < te.getMaxRenderDistanceSquared()
     *
     * so the right-hand side is served by AOC instead of the vanilla value.
     *
     * The callee CANNOT be matched by name. In production the dispatcher is
     * obfuscated and that call is `t()`, so a name comparison against
     * "getMaxRenderDistanceSquared" never fires and the patch silently does
     * nothing. Match by shape instead: a no-argument instance call that
     * returns a double, made on the TileEntity the dispatcher was handed -
     * which is this method's first (here: only object) parameter. The
     * immediately preceding real instruction has to be an ALOAD of that
     * parameter, which pins the receiver exactly and rules out any other
     * no-arg double-returning call in the method body.
     */
    private static void patchTileDistanceLimit(MethodNode m) {
        Type[] methodArgs = Type.getArgumentTypes(m.desc);
        if (methodArgs.length == 0 || methodArgs[0].getSort() != Type.OBJECT) {
            return;
        }

        // Descriptor of the TileEntity argument, e.g. "Lavj;" obfuscated or
        // "Lnet/minecraft/tileentity/TileEntity;" in a deobfuscated runtime.
        String tileEntityDesc = methodArgs[0].getDescriptor();
        boolean tileEntityIsLocal1 = methodArgs[0].getSize() == 1;

        for (AbstractInsnNode insn = m.instructions.getFirst();
             insn != null;
             insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;

            MethodInsnNode call = (MethodInsnNode) insn;

            // no-argument call returning double
            if (Type.getReturnType(call.desc).getSort() != Type.DOUBLE) continue;
            if (Type.getArgumentTypes(call.desc).length != 0) continue;

            // receiver must be the TileEntity parameter itself
            if (!tileEntityDesc.equals("L" + call.owner + ";")) continue;

            // and it must be loaded straight onto the stack for this call,
            // so we never touch an unrelated same-typed local
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
        if (args.length == 0 || args[0].getSort() != Type.OBJECT) return false;

        /*
         * Forge 1.12.2 TileEntityRendererDispatcher render overloads that
         * actually receive a TileEntity are limited to these signatures:
         *
         *   (TileEntity, float, int) -> void
         *   (TileEntity, double, double, double, float) -> void
         *   (TileEntity, double, double, double, float, float) -> void
         *   (TileEntity, double, double, double, float, int, float) -> void
         *
         * Match the exact primitive layout instead of accepting arbitrary
         * Object + numeric methods. This prevents the transformer from
         * accidentally inserting a RETURN into an unrelated dispatcher method.
         */
        if (args.length == 3) {
            return args[1].getSort() == Type.FLOAT
                    && args[2].getSort() == Type.INT;
        }

        if (args.length == 5) {
            return args[1].getSort() == Type.DOUBLE
                    && args[2].getSort() == Type.DOUBLE
                    && args[3].getSort() == Type.DOUBLE
                    && args[4].getSort() == Type.FLOAT;
        }

        if (args.length == 6) {
            return args[1].getSort() == Type.DOUBLE
                    && args[2].getSort() == Type.DOUBLE
                    && args[3].getSort() == Type.DOUBLE
                    && args[4].getSort() == Type.FLOAT
                    && args[5].getSort() == Type.FLOAT;
        }

        if (args.length == 7) {
            return args[1].getSort() == Type.DOUBLE
                    && args[2].getSort() == Type.DOUBLE
                    && args[3].getSort() == Type.DOUBLE
                    && args[4].getSort() == Type.FLOAT
                    && args[5].getSort() == Type.INT
                    && args[6].getSort() == Type.FLOAT;
        }

        return false;
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

    /*
     * ------------------------------------------------------------------
     * RenderGlobal: os 3 pontos onde um TileEntity entra no pipeline
     * ------------------------------------------------------------------
     *
     * O RenderGlobal vanilla nao culla TileEntity por parede. Ele so manda
     * o dispatcher desenhar o TE em tres lugares:
     *
     *   1) loop de field_72755_R (renderInfos)      -> TEs dentro do frustum
     *   2) loop de field_181024_n (setTileEntities) -> TEs globais / fast
     *   3) loop de breaking progress (field x)      -> TE em destruicao
     *
     * Nos tres, a pilha imediatamente antes do invokevirtual e sempre:
     *
     *   [ dispatcher , TileEntity , float partialTicks , int destroyStage ]
     *
     * (destroyStage = -1 nos sites 1 e 2, e oh.c() no site 3.)
     *
     * Entao guardamos os operandos, chamamos shouldCullTileEntity(te) e, se
     * ela mandar cullar, pulamos o invokevirtual inteiro. O TE continua
     * existindo, atualizando e com o estado intacto - so nao e desenhado.
     *
     * NAO tocamos em preDrawBatch()/drawBatch(): se a chamada nao acontece,
     * simplesmente nao existe entrada no batch, e o drawBatch final desenha
     * o que sobrou. O batch nunca quebra.
     */
    /**
     * Player name tags are rendered through RenderPlayer.renderEntityName().
     * AOC marks only that narrow call scope, so ordinary GUI/chat text is
     * never affected by the player-name opacity setting.
     */
    private byte[] patchRenderPlayerName(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            if (!isPlayerNameRenderShape(m.desc)) continue;
            if (containsHook(m, "beginPlayerNameRender")) continue;

            LabelNode hidden = new LabelNode();
            LabelNode render = new LabelNode();

            InsnList begin = new InsnList();
            begin.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CULL_ENGINE,
                    "shouldHidePlayerName",
                    "()Z",
                    false));
            begin.add(new JumpInsnNode(Opcodes.IFEQ, render));
            begin.add(new InsnNode(Opcodes.RETURN));
            begin.add(render);
            begin.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CULL_ENGINE,
                    "beginPlayerNameRender",
                    "()V",
                    false));
            m.instructions.insert(begin);

            for (AbstractInsnNode insn = m.instructions.getFirst();
                 insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList end = new InsnList();
                    end.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            CULL_ENGINE,
                            "endPlayerNameRender",
                            "()V",
                            false));
                    m.instructions.insertBefore(insn, end);
                }
            }
            patched++;
        }

        if (patched == 0) return bytes;
        System.out.println("[AOC] PATCHED RenderPlayer name-tag opacity scope: " + patched);
        return write(cn);
    }

    private static boolean isPlayerNameRenderShape(String desc) {
        try {
            Type[] args = Type.getArgumentTypes(desc);
            if (Type.getReturnType(desc).getSort() != Type.VOID) return false;
            return args.length == 6
                    && args[0].getSort() == Type.OBJECT
                    && args[1].getSort() == Type.DOUBLE
                    && args[2].getSort() == Type.DOUBLE
                    && args[3].getSort() == Type.DOUBLE
                    && args[4].getSort() == Type.OBJECT
                    && args[5].getSort() == Type.DOUBLE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Patches FontRenderer.renderString(String,float,float,int,boolean). */
    private byte[] patchFontRenderer(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;
        for (MethodNode m : cn.methods) {
            if (!"(Ljava/lang/String;FFIZ)I".equals(m.desc)) continue;
            if (containsHook(m, "adjustPlayerNameColor")) continue;

            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ILOAD, 4));
            hook.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CULL_ENGINE,
                    "adjustPlayerNameColor",
                    "(I)I",
                    false));
            hook.add(new VarInsnNode(Opcodes.ISTORE, 4));
            m.instructions.insert(hook);
            patched++;
        }
        if (patched == 0) return bytes;
        System.out.println("[AOC] PATCHED FontRenderer player-name color adjustment: " + patched);
        return write(cn);
    }

    private byte[] patchRenderGlobalTileCalls(byte[] bytes) {
        ClassNode cn = read(bytes);
        int patched = 0;

        for (MethodNode m : cn.methods) {
            patched += patchTileDispatcherCalls(m);
        }

        if (patched == 0) {
            if (!loggedRenderGlobalTileMiss) {
                loggedRenderGlobalTileMiss = true;
                System.out.println("[AOC] WARNING: RenderGlobal TileEntity dispatcher calls were not found.");
            }
            return bytes;
        }

        if (!loggedRenderGlobalTileHook) {
            loggedRenderGlobalTileHook = true;
            System.out.println("[AOC] PATCHED RenderGlobal TileEntity dispatcher calls: " + patched);
        }

        // Se o write() explodir, e melhor devolver o RenderGlobal vanilla
        // do que devolver um RenderGlobal meio remendado.
        try {
            return write(cn);
        } catch (Throwable t) {
            System.out.println("[AOC] WARNING: RenderGlobal rewrite failed (" + t
                    + "), keeping vanilla RenderGlobal.");
            return bytes;
        }
    }

    private static int patchTileDispatcherCalls(MethodNode method) {
        int patched = 0;
        int nextLocal = method.maxLocals;
        AbstractInsnNode insn = method.instructions.getFirst();

        while (insn != null) {
            AbstractInsnNode next = insn.getNext();

            if (!(insn instanceof MethodInsnNode)) {
                insn = next;
                continue;
            }

            MethodInsnNode call = (MethodInsnNode) insn;
            if (!isTileDispatcherRenderCall(call)) {
                insn = next;
                continue;
            }

            // Se o guard ja esta la (transformer rodando duas vezes no mesmo
            // metodo), nao empilha um segundo.
            if (alreadyGuarded(call)) {
                insn = next;
                continue;
            }

            int receiverLocal = nextLocal++;
            int tileLocal = nextLocal++;
            int partialLocal = nextLocal++;
            int destroyLocal = nextLocal++;

            LabelNode skip = new LabelNode();
            InsnList patch = new InsnList();

            // Desmonta a pilha: topo = destroyStage, depois partialTicks,
            // depois o TE, depois o dispatcher.
            patch.add(new VarInsnNode(Opcodes.ISTORE, destroyLocal));
            patch.add(new VarInsnNode(Opcodes.FSTORE, partialLocal));
            patch.add(new VarInsnNode(Opcodes.ASTORE, tileLocal));
            patch.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));

            patch.add(new VarInsnNode(Opcodes.ALOAD, tileLocal));
            patch.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CULL_ENGINE,
                    "shouldCullTileEntity",
                    "(Lnet/minecraft/tileentity/TileEntity;)Z",
                    false
            ));
            // true -> nao desenha (pula a chamada original)
            patch.add(new JumpInsnNode(Opcodes.IFNE, skip));

            // Caminho normal: remonta a pilha na ordem original e faz a
            // chamada vanilla exatamente como estava.
            patch.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            patch.add(new VarInsnNode(Opcodes.ALOAD, tileLocal));
            patch.add(new VarInsnNode(Opcodes.FLOAD, partialLocal));
            patch.add(new VarInsnNode(Opcodes.ILOAD, destroyLocal));
            patch.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));

            // Caminho cullado: nao desenha, nao remove, nao altera estado.
            patch.add(skip);

            method.instructions.insertBefore(insn, patch);
            method.instructions.remove(insn);
            patched++;
            insn = next;
        }

        method.maxLocals = Math.max(method.maxLocals, nextLocal);
        return patched;
    }

    /**
     * Confere se a chamada ja tem um shouldCullTileEntity logo antes dela.
     */
    private static boolean alreadyGuarded(MethodInsnNode call) {
        int seen = 0;
        AbstractInsnNode prev = call.getPrevious();

        while (prev != null && seen < 16) {
            if (prev instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) prev;
                if (CULL_ENGINE.equals(mi.owner) && "shouldCullTileEntity".equals(mi.name)) {
                    return true;
                }
            }
            prev = prev.getPrevious();
            seen++;
        }

        return false;
    }

    /**
     * Reconhece exatamente o invokevirtual do dispatcher:
     *
     *   bwx.a : (Lavj;FI)V
     *   TileEntityRendererDispatcher.render : (LTileEntity;FI)V
     *
     * Forma: void, 3 args, args[0] = object (TileEntity),
     * args[1] = float (partialTicks), args[2] = int (destroyStage).
     * Bate nos 3 sites do RenderGlobal.
     */
    private static boolean isTileDispatcherRenderCall(MethodInsnNode call) {
        if (call == null) return false;
        if (Type.getReturnType(call.desc).getSort() != Type.VOID) return false;

        Type[] args;
        try {
            args = Type.getArgumentTypes(call.desc);
        } catch (Throwable ignored) {
            return false;
        }

        return args.length == 3
                && args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.FLOAT
                && args[2].getSort() == Type.INT;
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