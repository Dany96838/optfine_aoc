package androidoptimizationcore;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AOC settings screen using the same compact two-column interaction pattern
 * familiar from the vanilla/OptiFine Video Settings screen.
 *
 * Every option uses an identical 200x20 button. Value options cycle through
 * predefined choices on click, just like the video-settings selectors.
 */
public final class AOCGui extends GuiScreen {
    private final GuiScreen parent;
    private final List<String> tooltip = new ArrayList<String>();

    public AOCGui(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        final int bw = 200;
        final int bh = 20;
        final int gap = 8;
        final int left = width / 2 - bw - gap / 2;
        final int right = width / 2 + gap / 2;
        final int y = 48;
        final int step = 24;

        // Same width/height for every setting button.
        buttonList.add(new GuiButton(1, left,  y,          bw, bh, toggle("key.aoc.entity", AOCConfig.entityCulling)));
        buttonList.add(new GuiButton(2, right, y,          bw, bh, toggle("key.aoc.tile", AOCConfig.tileEntityCulling)));

        buttonList.add(new GuiButton(3, left,  y + step,    bw, bh, toggle("key.aoc.occlusion", AOCConfig.occlusionCulling)));
        buttonList.add(new GuiButton(4, right, y + step,    bw, bh, toggle("key.aoc.animations", AOCConfig.animatedTextures)));

        buttonList.add(new GuiButton(5, left,  y + step*2,  bw, bh,
                value("key.aoc.entity_distance", AOCConfig.entityDistance, "blocks")));
        buttonList.add(new GuiButton(6, right, y + step*2,  bw, bh,
                value("key.aoc.tile_distance", AOCConfig.tileEntityDistance, "blocks")));

        buttonList.add(new GuiButton(7, left,  y + step*3,  bw, bh,
                value("key.aoc.occlusion_budget", AOCConfig.occlusionBudget, "checks")));

        // Reset is a real setting action, kept in the same grid and same size.
        buttonList.add(new GuiButton(8, right, y + step*3, bw, bh,
                I18n.format("key.aoc.reset")));

        // Centered Done button, also exactly 200x20.
        buttonList.add(new GuiButton(9, width / 2 - bw / 2, y + step*4 + 5, bw, bh,
                I18n.format("key.aoc.done")));
    }

    private String toggle(String key, boolean value) {
        return I18n.format(key) + ": " + I18n.format(value ? "key.aoc.on" : "key.aoc.off");
    }

    private String value(String key, int value, String unit) {
        return I18n.format(key) + ": " + value + " " + unit;
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case 1:
                AOCConfig.entityCulling = !AOCConfig.entityCulling;
                break;
            case 2:
                AOCConfig.tileEntityCulling = !AOCConfig.tileEntityCulling;
                break;
            case 3:
                AOCConfig.occlusionCulling = !AOCConfig.occlusionCulling;
                break;
            case 4:
                AOCConfig.animatedTextures = !AOCConfig.animatedTextures;
                break;
            case 5:
                AOCConfig.entityDistance = nextDistance(AOCConfig.entityDistance);
                break;
            case 6:
                AOCConfig.tileEntityDistance = nextDistance(AOCConfig.tileEntityDistance);
                break;
            case 7:
                AOCConfig.occlusionBudget = nextBudget(AOCConfig.occlusionBudget);
                break;
            case 8:
                AOCConfig.resetDefaults();
                break;
            case 9:
                AOCConfig.save();
                mc.displayGuiScreen(parent);
                return;
        }

        AOCConfig.save();
        initGui();
    }

    /**
     * Visibility ranges are verification radii, not render-distance limits.
     * Outside the selected range AOC simply stops doing its extra occlusion
     * work and lets normal Minecraft/OptiFine rendering continue.
     */
    private int nextDistance(int v) {
        final int[] values = {
                8, 16, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512
        };
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) return values[(i + 1) % values.length];
        }
        return 32;
    }

    private int nextBudget(int v) {
        final int[] values = {0, 4, 8, 12, 16, 24, 32, 48, 64};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) return values[(i + 1) % values.length];
        }
        return 16;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(fontRenderer, I18n.format("key.aoc.title"),
                width / 2, 20, 0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("key.aoc.subtitle"),
                width / 2, 34, 0xA0A0A0);

        super.drawScreen(mouseX, mouseY, partialTicks);

        GuiButton hovered = null;
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.isMouseOver()) {
                hovered = b;
                break;
            }
        }

        if (hovered == null) return;

        String key;
        switch (hovered.id) {
            case 1: key = "key.aoc.tooltip.entity"; break;
            case 2: key = "key.aoc.tooltip.tile"; break;
            case 3: key = "key.aoc.tooltip.occlusion"; break;
            case 4: key = "key.aoc.tooltip.animations"; break;
            case 5:
            case 6: key = "key.aoc.tooltip.distance"; break;
            case 7: key = "key.aoc.tooltip.budget"; break;
            case 8: key = "key.aoc.tooltip.reset"; break;
            default: return;
        }

        tooltip.clear();
        tooltip.add(I18n.format(key));
        GuiUtils.drawHoveringText(tooltip, mouseX, mouseY,
                width, height, 280, fontRenderer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
