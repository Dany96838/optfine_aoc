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
 * AOC settings screen opened by the configurable AOC key binding.
 *
 * Numeric settings use Forge 1.12.2 GuiSlider so ranges are genuinely
 * draggable. The screen is independent from the Video Settings menu.
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
        final int y = 46;
        final int step = 24;

        // Row 1: entities.
        buttonList.add(new GuiButton(1, left, y, bw, bh,
                toggle("key.aoc.entity", AOCConfig.entityCulling)));
        buttonList.add(new GuiSlider(4, right, y, bw, bh,
                I18n.format("key.aoc.entity_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.entityDistance,
                false, true, this));

        // Row 2: TileEntities.
        buttonList.add(new GuiButton(2, left, y + step, bw, bh,
                toggle("key.aoc.tile", AOCConfig.tileEntityCulling)));
        buttonList.add(new GuiSlider(5, right, y + step, bw, bh,
                I18n.format("key.aoc.tile_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.tileEntityDistance,
                false, true, this));

        // Row 3: particles.
        buttonList.add(new GuiButton(3, left, y + step * 2, bw, bh,
                toggle("key.aoc.particle", AOCConfig.particleCulling)));
        buttonList.add(new GuiSlider(6, right, y + step * 2, bw, bh,
                I18n.format("key.aoc.particle_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.particleDistance,
                false, true, this));

        // Row 4: visual effects.
        buttonList.add(new GuiButton(7, left, y + step * 3, bw, bh,
                toggle("key.aoc.effect", AOCConfig.effectCulling)));
        buttonList.add(new GuiSlider(8, right, y + step * 3, bw, bh,
                I18n.format("key.aoc.effect_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.effectDistance,
                false, true, this));

        // Row 5: opaque-block occlusion and its work budget.
        buttonList.add(new GuiButton(9, left, y + step * 4, bw, bh,
                toggle("key.aoc.occlusion", AOCConfig.occlusionCulling)));
        buttonList.add(new GuiSlider(10, right, y + step * 4, bw, bh,
                I18n.format("key.aoc.occlusion_budget") + ": ",
                " " + I18n.format("key.aoc.unit.checks"),
                0.0D, 64.0D, AOCConfig.occlusionBudget,
                false, true, this));

        buttonList.add(new GuiSlider(11, left, y + step * 5, bw, bh,
                I18n.format("key.aoc.entity_extra_range") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                0.0D, 128.0D, AOCConfig.entityExtraRange,
                false, true, this));
        buttonList.add(new GuiButton(12, left, y + step * 6, bw, bh,
                I18n.format("key.aoc.reset")));
        buttonList.add(new GuiButton(13, right, y + step * 6, bw, bh,
                I18n.format("key.aoc.done")));
    }

    private String toggle(String key, boolean value) {
        return I18n.format(key) + ": "
                + I18n.format(value ? "key.aoc.on" : "key.aoc.off");
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                AOCConfig.entityCulling = !AOCConfig.entityCulling;
                break;
            case 2:
                AOCConfig.tileEntityCulling = !AOCConfig.tileEntityCulling;
                break;
            case 3:
                AOCConfig.particleCulling = !AOCConfig.particleCulling;
                break;
            case 7:
                AOCConfig.effectCulling = !AOCConfig.effectCulling;
                break;
            case 9:
                AOCConfig.occlusionCulling = !AOCConfig.occlusionCulling;
                break;
            case 12:
                AOCConfig.resetDefaults();
                break;
            case 13:
                AOCConfig.save();
                mc.displayGuiScreen(parent);
                return;
            default:
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
                    AOCConfig.entityDistance =
                            clampInt(slider.getValueInt(), 8, 640);
                    break;
                case 5:
                    AOCConfig.tileEntityDistance =
                            clampInt(slider.getValueInt(), 8, 640);
                    break;
                case 6:
                    AOCConfig.particleDistance =
                            clampInt(slider.getValueInt(), 8, 160);
                    break;
                case 8:
                    AOCConfig.effectDistance =
                            clampInt(slider.getValueInt(), 8, 160);
                    break;
                case 10:
                    AOCConfig.occlusionBudget =
                            clampInt(slider.getValueInt(), 0, 64);
                    break;
                case 11:
                    AOCConfig.entityExtraRange =
                            clampInt(slider.getValueInt(), 0, 128);
                    break;
                default:
                    return;
            }

            AOCConfig.applyToRuntime();
        } catch (Throwable ignored) {
            // A GUI interaction must never crash the client.
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(fontRenderer, I18n.format("key.aoc.title"),
                width / 2, 18, 0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("key.aoc.subtitle"),
                width / 2, 32, 0xA0A0A0);

        super.drawScreen(mouseX, mouseY, partialTicks);

        GuiButton hovered = null;
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.isMouseOver()) {
                hovered = button;
                break;
            }
        }

        if (hovered == null) return;

        String key;
        switch (hovered.id) {
            case 1: key = "key.aoc.tooltip.entity"; break;
            case 2: key = "key.aoc.tooltip.tile"; break;
            case 3: key = "key.aoc.tooltip.particle"; break;
            case 4:
            case 5:
            case 6:
            case 8: key = "key.aoc.tooltip.distance"; break;
            case 7: key = "key.aoc.tooltip.effect"; break;
            case 9: key = "key.aoc.tooltip.occlusion"; break;
            case 10: key = "key.aoc.tooltip.budget"; break;
            case 11: key = "key.aoc.tooltip.extra_range"; break;
            case 12: key = "key.aoc.tooltip.reset"; break;
            case 13: return;
            default: return;
        }

        tooltip.clear();
        tooltip.add(I18n.format(key));
        GuiUtils.drawHoveringText(
                tooltip, mouseX, mouseY, width, height, 300, fontRenderer);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
