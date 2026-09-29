package androidoptimizationcore;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/**
 * Client-only key bindings owned by AOC.
 */
public final class AOCClientKeybinds {
    public static final KeyBinding OPEN_MENU = new KeyBinding(
            "Android Optimization Core",
            Keyboard.KEY_F4,
            "Android Optimization Core"
    );

    private AOCClientKeybinds() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN_MENU);
    }
}
