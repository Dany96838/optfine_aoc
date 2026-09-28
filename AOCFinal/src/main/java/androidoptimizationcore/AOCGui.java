package androidoptimizationcore;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AOC settings screen following the familiar vanilla/OptiFine Video Settings
 * layout: two columns of compact options and a centered Done button.
 */
public final class AOCGui extends GuiScreen {
    private final GuiScreen parent;
    private final List<String> tooltip = new ArrayList<String>();

    public AOCGui(GuiScreen parent) { this.parent = parent; }

    @Override public void initGui() {
        buttonList.clear();

        final int bw = 200;
        final int gap = 8;
        final int left = width / 2 - bw - gap / 2;
        final int right = width / 2 + gap / 2;
        final int y = 48;
        final int step = 25;

        buttonList.add(new GuiButton(1, left,  y,       bw, 20, text("key.aoc.entity", AOCConfig.entityCulling)));
        buttonList.add(new GuiButton(2, right, y,       bw, 20, text("key.aoc.tile", AOCConfig.tileEntityCulling)));
        buttonList.add(new GuiButton(3, left,  y+step, bw, 20, text("key.aoc.occlusion", AOCConfig.occlusionCulling)));
        buttonList.add(new GuiButton(4, right, y+step, bw, 20, text("key.aoc.animations", AOCConfig.animatedTextures)));
        buttonList.add(new GuiButton(5, left,  y+step*2, bw, 20,
                I18n.format("key.aoc.entity_distance") + ": " + AOCConfig.entityDistance));
        buttonList.add(new GuiButton(6, right, y+step*2, bw, 20,
                I18n.format("key.aoc.tile_distance") + ": " + AOCConfig.tileEntityDistance));
        buttonList.add(new GuiButton(7, left,  y+step*3, bw, 20,
                I18n.format("key.aoc.occlusion_budget") + ": " + AOCConfig.occlusionBudget));
        buttonList.add(new GuiButton(9, width / 2 - 100, y+step*4+5, 200, 20,
                I18n.format("key.aoc.done")));
    }

    private String text(String key, boolean value) {
        return I18n.format(key) + ": " + I18n.format(value ? "key.aoc.on" : "key.aoc.off");
    }

    @Override protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case 1: AOCConfig.entityCulling = !AOCConfig.entityCulling; break;
            case 2: AOCConfig.tileEntityCulling = !AOCConfig.tileEntityCulling; break;
            case 3: AOCConfig.occlusionCulling = !AOCConfig.occlusionCulling; break;
            case 4: AOCConfig.animatedTextures = !AOCConfig.animatedTextures; break;
            case 5: AOCConfig.entityDistance = next(AOCConfig.entityDistance); break;
            case 6: AOCConfig.tileEntityDistance = next(AOCConfig.tileEntityDistance); break;
            case 7: AOCConfig.occlusionBudget = nextBudget(AOCConfig.occlusionBudget); break;
            case 9: AOCConfig.save(); mc.displayGuiScreen(parent); return;
        }
        AOCConfig.save();
        initGui();
    }

    private int next(int v) {
        int[] a = {8, 16, 32, 64, 128, 256};
        for (int i = 0; i < a.length; i++) if (a[i] == v) return a[(i + 1) % a.length];
        return 32;
    }

    private int nextBudget(int v) {
        int[] a = {0, 8, 16, 24, 32, 48, 64};
        for (int i = 0; i < a.length; i++) if (a[i] == v) return a[(i + 1) % a.length];
        return 16;
    }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, I18n.format("key.aoc.title"), width / 2, 20, 0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("key.aoc.subtitle"), width / 2, 34, 0xA0A0A0);
        super.drawScreen(mouseX, mouseY, partialTicks);

        GuiButton hovered = null;
        for (Object o : buttonList) {
            GuiButton b = (GuiButton) o;
            if (b.isMouseOver()) { hovered = b; break; }
        }
        if (hovered != null) {
            String key;
            switch (hovered.id) {
                case 1: key = "key.aoc.tooltip.entity"; break;
                case 2: key = "key.aoc.tooltip.tile"; break;
                case 3: key = "key.aoc.tooltip.occlusion"; break;
                case 4: key = "key.aoc.tooltip.animations"; break;
                case 7: key = "key.aoc.tooltip.budget"; break;
                case 9: return;
                default: key = "key.aoc.tooltip.distance";
            }
            tooltip.clear();
            tooltip.add(I18n.format(key));
            GuiUtils.drawHoveringText(tooltip, mouseX, mouseY, width, height, 240, fontRenderer);
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }
}
