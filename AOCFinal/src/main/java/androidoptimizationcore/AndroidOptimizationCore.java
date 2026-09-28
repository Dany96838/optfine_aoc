package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiVideoSettings;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid=AndroidOptimizationCore.MOD_ID,name=AndroidOptimizationCore.NAME,version=AndroidOptimizationCore.VERSION,clientSideOnly=true,dependencies="required-after:forge@[14.23.5.2864,);after:optifine")
public final class AndroidOptimizationCore {
    public static final String MOD_ID="androidoptimizationcore";
    public static final String NAME="Android Optimization Core";
    public static final String VERSION="1.2.0";

    private static final int AOC_VIDEO_BUTTON_ID = 0xA0C0;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        OptiFineCompat.requireG5();
        AOCConfig.load(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }

    public static final class ClientEvents {
        private boolean lastF6;

        @SubscribeEvent
        public void renderTick(RenderWorldLastEvent event) {
            RenderCullingEngine.updateCamera();
        }

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

        @SubscribeEvent
        public void videoSettings(GuiScreenEvent.InitGuiEvent.Post event) {
            GuiScreen screen = event.getGui();
            if (!(screen instanceof GuiVideoSettings)) return;

            // OptiFine 1.12.2 uses button id 210 for "Reset Video Settings".
            // Place AOC immediately beside that button instead of using a fixed Y.
            GuiButton reset = null;
            for (Object obj : event.getButtonList()) {
                if (!(obj instanceof GuiButton)) continue;
                GuiButton button = (GuiButton) obj;
                if (button.id == 210 || "Reset Video Settings...".equals(button.displayString)) {
                    reset = button;
                    break;
                }
            }

            if (reset != null) {
                event.getButtonList().add(new GuiButton(
                        AOC_VIDEO_BUTTON_ID,
                        reset.x + reset.width + 5,
                        reset.y,
                        100,
                        reset.height,
                        I18n.format("key.aoc.open")
                ));
            } else {
                // Safe fallback for environments where OptiFine changes the reset button.
                event.getButtonList().add(new GuiButton(
                        AOC_VIDEO_BUTTON_ID,
                        screen.width / 2 - 100,
                        screen.height - 52,
                        200,
                        20,
                        I18n.format("key.aoc.open")
                ));
            }
        }

        @SubscribeEvent
        public void videoClick(GuiScreenEvent.ActionPerformedEvent.Post event) {
            if (event.getGui() instanceof GuiVideoSettings
                    && event.getButton().id == AOC_VIDEO_BUTTON_ID) {
                Minecraft.getMinecraft().displayGuiScreen(new AOCGui(event.getGui()));
            }
        }
    }
}
