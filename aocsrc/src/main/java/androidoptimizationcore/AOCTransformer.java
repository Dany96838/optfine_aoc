package androidoptimizationcore;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.minecraft.launchwrapper.IClassTransformer;

import java.util.List;

public final class AOCTransformer implements IClassTransformer {
    private static final String RM = "net.minecraft.client.renderer.entity.RenderManager";
    private static final String TE = "net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher";
    private static final String TM = "net.minecraft.client.renderer.texture.TextureMap";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (RM.equals(name) || RM.equals(transformedName)) return patchRenderManager(basicClass);
            if (TE.equals(name) || TE.equals(transformedName)) return patchTileDispatcher(basicClass);
            if (TM.equals(name) || TM.equals(transformedName)) return patchTextureMap(basicClass);
        } catch (Throwable t) {
            // Fail-open: a rendering optimization must never prevent the class from loading.
            return basicClass;
        }
        return basicClass;
    }

    private byte[] patchRenderManager(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            if (!("renderEntity".equals(m.name) || "func_188391_a".equals(m.name))) continue;
            if (!m.desc.startsWith("(Lnet/minecraft/entity/Entity;DDDFFZ)V")) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "androidoptimizationcore/RenderCullingEngine", "shouldCullEntity", "(Lnet/minecraft/entity/Entity;)Z", false));
            LabelNode pass = new LabelNode();
            hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            hook.add(new InsnNode(Opcodes.RETURN));
            hook.add(pass);
            m.instructions.insert(hook);
            break;
        }
        return write(cn);
    }

    private byte[] patchTileDispatcher(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            if (!("render".equals(m.name) || "func_192854_a".equals(m.name) || "func_192855_a".equals(m.name))) continue;
            if (!m.desc.startsWith("(Lnet/minecraft/tileentity/TileEntity;")) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "androidoptimizationcore/RenderCullingEngine", "shouldCullTileEntity", "(Lnet/minecraft/tileentity/TileEntity;)Z", false));
            LabelNode pass = new LabelNode();
            hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            hook.add(new InsnNode(Opcodes.RETURN));
            hook.add(pass);
            m.instructions.insert(hook);
            break;
        }
        return write(cn);
    }

    private byte[] patchTextureMap(byte[] bytes) {
        ClassNode cn = read(bytes);
        for (MethodNode m : cn.methods) {
            if (!("tick".equals(m.name) || "func_73660_a".equals(m.name))) continue;
            if (!"()V".equals(m.desc)) continue;
            InsnList hook = new InsnList();
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "androidoptimizationcore/AnimatedTextureController", "shouldSkipAtlasUpdate", "()Z", false));
            LabelNode pass = new LabelNode();
            hook.add(new JumpInsnNode(Opcodes.IFEQ, pass));
            hook.add(new InsnNode(Opcodes.RETURN));
            hook.add(pass);
            m.instructions.insert(hook);
            break;
        }
        return write(cn);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
