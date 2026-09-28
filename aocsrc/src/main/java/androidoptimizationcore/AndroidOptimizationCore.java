package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = AndroidOptimizationCore.MOD_ID, name = AndroidOptimizationCore.NAME,
     version = AndroidOptimizationCore.VERSION, clientSideOnly = true,
     dependencies = "required-after:forge@[14.23.5.2864,);after:optifine")
public final class AndroidOptimizationCore {
    public static final String MOD_ID = "androidoptimizationcore";
    public static final String NAME = "Android Optimization Core";
    public static final String VERSION = "1.2.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        OptiFineCompat.requireG5();
        AOCConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        MinecraftForge.EVENT_BUS.register(new AOCGuiEvents());
    }

    public static final class ClientEvents {
        private boolean lastF6;

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            RenderCullingEngine.endClientTick();
            AnimatedTextureController.tick();
        }

        @SubscribeEvent
        public void key(InputEvent.KeyInputEvent event) {
            boolean f6 = Keyboard.isKeyDown(Keyboard.KEY_F6);
            if (f6 && !lastF6 && Minecraft.getMinecraft().currentScreen == null) {
                Minecraft.getMinecraft().displayGuiScreen(new AOCGui(null));
            }
            lastF6 = f6;
        }
    }

    public static final class AOCGuiEvents {}
}
