package androidoptimizationcore;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/**
 * Client-only key bindings owned by AOC.
 */
public final class AOCClientKeybinds {
    public static final KeyBinding OPEN_MENU = new KeyBinding(
            "key.aoc.open",
            Keyboard.KEY_F4,
            "key.categories.aoc"
    );

    private AOCClientKeybinds() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(OPEN_MENU);
    }
}
