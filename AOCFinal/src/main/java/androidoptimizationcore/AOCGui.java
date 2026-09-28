package androidoptimizationcore;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.client.config.GuiUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AOC settings screen using the same compact two-column layout as the
 * vanilla video settings screen.
 *
 * Numeric options use Forge's 1.12.2 GuiSlider, so they are real draggable
 * selectors rather than click-to-cycle buttons.
 */
public final class AOCGui extends GuiScreen implements GuiSlider.ISlider {
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

        buttonList.add(new GuiButton(1, left, y, bw, bh,
                toggle("key.aoc.entity", AOCConfig.entityCulling)));
        buttonList.add(new GuiButton(2, right, y, bw, bh,
                toggle("key.aoc.tile", AOCConfig.tileEntityCulling)));

        buttonList.add(new GuiButton(3, left, y + step, bw, bh,
                toggle("key.aoc.occlusion", AOCConfig.occlusionCulling)));

        buttonList.add(new GuiSlider(4, right, y + step, bw, bh,
                I18n.format("key.aoc.entity_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 512.0D, AOCConfig.entityDistance,
                false, true, this));

        buttonList.add(new GuiSlider(5, left, y + step * 2, bw, bh,
                I18n.format("key.aoc.tile_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 512.0D, AOCConfig.tileEntityDistance,
                false, true, this));

        buttonList.add(new GuiSlider(6, right, y + step * 2, bw, bh,
                I18n.format("key.aoc.occlusion_budget") + ": ",
                " " + I18n.format("key.aoc.unit.checks"),
                0.0D, 64.0D, AOCConfig.occlusionBudget,
                false, true, this));

        buttonList.add(new GuiButton(7, left, y + step * 3, bw, bh,
                I18n.format("key.aoc.reset")));

        buttonList.add(new GuiButton(8, right, y + step * 3, bw, bh,
                I18n.format("key.aoc.done")));
    }

    private String toggle(String key, boolean value) {
        return I18n.format(key) + ": " + I18n.format(value ? "key.aoc.on" : "key.aoc.off");
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
            case 7:
                AOCConfig.resetDefaults();
                break;
            case 8:
                AOCConfig.save();
                mc.displayGuiScreen(parent);
                return;
            default:
                // GuiSlider changes are delivered through onChangeSliderValue().
                return;
        }

        AOCConfig.save();
        initGui();
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        try {
            switch (slider.id) {
                case 4:
                    AOCConfig.entityDistance = clampInt(slider.getValueInt(), 8, 512);
                    break;
                case 5:
                    AOCConfig.tileEntityDistance = clampInt(slider.getValueInt(), 8, 512);
                    break;
                case 6:
                    AOCConfig.occlusionBudget = clampInt(slider.getValueInt(), 0, 64);
                    break;
                default:
                    return;
            }
        } catch (Throwable ignored) {
            // GUI interaction must never crash the client.
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
            case 4:
            case 5: key = "key.aoc.tooltip.distance"; break;
            case 6: key = "key.aoc.tooltip.budget"; break;
            case 7: key = "key.aoc.tooltip.reset"; break;
            case 8: return;
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
