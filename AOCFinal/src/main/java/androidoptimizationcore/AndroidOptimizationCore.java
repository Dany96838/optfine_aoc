package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(
        modid = AndroidOptimizationCore.MOD_ID,
        name = AndroidOptimizationCore.NAME,
        version = AndroidOptimizationCore.VERSION,
        clientSideOnly = true,
        dependencies = "required-after:forge@[14.23.5.2864,)"
)
public final class AndroidOptimizationCore {
    public static final String MOD_ID = "androidoptimizationcore";
    public static final String NAME = "Android Optimization Core";
    public static final String VERSION = "1.3.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AOCConfig.load(event.getSuggestedConfigurationFile());
        AOCClientKeybinds.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }

    public static final class ClientEvents {
        @SubscribeEvent
        public void renderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                RenderCullingEngine.updateCamera();
            }
        }

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            RenderCullingEngine.endClientTick();
        }

        @SubscribeEvent
        public void key(InputEvent.KeyInputEvent event) {
            while (AOCClientKeybinds.OPEN_MENU.isPressed()) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.currentScreen == null) {
                    mc.displayGuiScreen(new AOCGui(null));
                }
            }
        }
    }
}
