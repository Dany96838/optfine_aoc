package androidoptimizationcore;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiSlider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone AOC settings screen.
 *
 * Numeric settings use Forge 1.12.2 GuiSlider so the controls are genuinely
 * draggable. Tooltips intentionally use a two-second hover delay to keep the
 * screen visually clean while still explaining every control.
 */
public final class AOCGui extends GuiScreen implements GuiSlider.ISlider {
    private static final long TOOLTIP_DELAY_MS = 2000L;

    private final GuiScreen parent;
    private final List<String> tooltip = new ArrayList<String>();

    private int hoveredId = -1;
    private long hoverStartedAt;

    public AOCGui(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        hoveredId = -1;
        hoverStartedAt = 0L;

        final int bw = 200;
        final int bh = 20;
        final int gap = 8;
        final int left = width / 2 - bw - gap / 2;
        final int right = width / 2 + gap / 2;
        final int y = 44;
        final int step = 24;

        buttonList.add(new GuiButton(1, left, y, bw, bh,
                toggle("key.aoc.entity", AOCConfig.entityCulling)));
        buttonList.add(new GuiSlider(10, right, y, bw, bh,
                I18n.format("key.aoc.entity_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.entityDistance,
                false, true, this));

        buttonList.add(new GuiButton(2, left, y + step, bw, bh,
                toggle("key.aoc.item", AOCConfig.itemCulling)));
        buttonList.add(new GuiSlider(11, right, y + step, bw, bh,
                I18n.format("key.aoc.item_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.itemDistance,
                false, true, this));

        buttonList.add(new GuiButton(3, left, y + step * 2, bw, bh,
                toggle("key.aoc.tile", AOCConfig.tileEntityCulling)));
        buttonList.add(new GuiSlider(12, right, y + step * 2, bw, bh,
                I18n.format("key.aoc.tile_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.tileEntityDistance,
                false, true, this));

        buttonList.add(new GuiButton(4, left, y + step * 3, bw, bh,
                toggle("key.aoc.particle", AOCConfig.particleCulling)));
        buttonList.add(new GuiSlider(13, right, y + step * 3, bw, bh,
                I18n.format("key.aoc.particle_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.particleDistance,
                false, true, this));

        buttonList.add(new GuiButton(5, left, y + step * 4, bw, bh,
                toggle("key.aoc.effect", AOCConfig.effectCulling)));
        buttonList.add(new GuiSlider(14, right, y + step * 4, bw, bh,
                I18n.format("key.aoc.effect_distance") + ": ",
                " " + I18n.format("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.effectDistance,
                false, true, this));

        buttonList.add(new GuiButton(6, left, y + step * 5, bw, bh,
                toggle("key.aoc.occlusion", AOCConfig.occlusionCulling)));
        buttonList.add(new GuiSlider(15, right, y + step * 5, bw, bh,
                I18n.format("key.aoc.occlusion_budget") + ": ",
                " " + I18n.format("key.aoc.unit.checks"),
                0.0D, 64.0D, AOCConfig.occlusionBudget,
                false, true, this));

        buttonList.add(new GuiButton(8, left, y + step * 6, bw, bh,
                I18n.format("key.aoc.reset")));
        buttonList.add(new GuiButton(9, right, y + step * 6, bw, bh,
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
                AOCConfig.itemCulling = !AOCConfig.itemCulling;
                break;
            case 3:
                AOCConfig.tileEntityCulling = !AOCConfig.tileEntityCulling;
                break;
            case 4:
                AOCConfig.particleCulling = !AOCConfig.particleCulling;
                break;
            case 5:
                AOCConfig.effectCulling = !AOCConfig.effectCulling;
                break;
            case 6:
                AOCConfig.occlusionCulling = !AOCConfig.occlusionCulling;
                break;
            case 8:
                AOCConfig.resetDefaults();
                break;
            case 9:
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
                case 10:
                    AOCConfig.entityDistance = clampInt(slider.getValueInt(), 8, 640);
                    break;
                case 11:
                    AOCConfig.itemDistance = clampInt(slider.getValueInt(), 8, 640);
                    break;
                case 12:
                    AOCConfig.tileEntityDistance = clampInt(slider.getValueInt(), 8, 640);
                    break;
                case 13:
                    AOCConfig.particleDistance = clampInt(slider.getValueInt(), 8, 160);
                    break;
                case 14:
                    AOCConfig.effectDistance = clampInt(slider.getValueInt(), 8, 160);
                    break;
                case 15:
                    AOCConfig.occlusionBudget = clampInt(slider.getValueInt(), 0, 64);
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
                width / 2, 16, 0xFFFFFF);
        drawCenteredString(fontRenderer, I18n.format("key.aoc.subtitle"),
                width / 2, 30, 0xA0A0A0);

        super.drawScreen(mouseX, mouseY, partialTicks);

        GuiButton hovered = null;
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.isMouseOver()) {
                hovered = button;
                break;
            }
        }

        if (hovered == null) {
            hoveredId = -1;
            return;
        }

        if (hoveredId != hovered.id) {
            hoveredId = hovered.id;
            hoverStartedAt = System.currentTimeMillis();
            return;
        }

        if (System.currentTimeMillis() - hoverStartedAt < TOOLTIP_DELAY_MS) {
            return;
        }

        tooltip.clear();
        addTooltipFor(hovered);
        if (!tooltip.isEmpty()) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRenderer);
        }
    }

    private void addTooltipFor(GuiButton button) {
        switch (button.id) {
            case 1:
                tooltip.add(I18n.format("key.aoc.tooltip.entity"));
                return;
            case 2:
                tooltip.add(I18n.format("key.aoc.tooltip.item"));
                return;
            case 3:
                tooltip.add(I18n.format("key.aoc.tooltip.tile"));
                return;
            case 4:
                tooltip.add(I18n.format("key.aoc.tooltip.particle"));
                return;
            case 5:
                tooltip.add(I18n.format("key.aoc.tooltip.effect"));
                return;
            case 6:
                tooltip.add(I18n.format("key.aoc.tooltip.occlusion"));
                return;
            case 8:
                tooltip.add(I18n.format("key.aoc.tooltip.reset"));
                return;
            case 9:
                return;
            case 10:
                addDistanceTooltip("key.aoc.tooltip.entity_distance",
                        AOCConfig.entityDistance, 640);
                return;
            case 11:
                addDistanceTooltip("key.aoc.tooltip.item_distance",
                        AOCConfig.itemDistance, 640);
                return;
            case 12:
                addDistanceTooltip("key.aoc.tooltip.tile_distance",
                        AOCConfig.tileEntityDistance, 640);
                return;
            case 13:
                addDistanceTooltip("key.aoc.tooltip.particle_distance",
                        AOCConfig.particleDistance, 160);
                return;
            case 14:
                addDistanceTooltip("key.aoc.tooltip.effect_distance",
                        AOCConfig.effectDistance, 160);
                return;
            case 15:
                addBudgetTooltip(AOCConfig.occlusionBudget);
                return;
            default:
                return;
        }
    }

    private void addDistanceTooltip(String descriptionKey, int value, int max) {
        tooltip.add(I18n.format(descriptionKey));
        tooltip.add(I18n.format(impactKey(value, 8, max), value));
    }

    private void addBudgetTooltip(int value) {
        tooltip.add(I18n.format("key.aoc.tooltip.budget"));
        tooltip.add(I18n.format(budgetImpactKey(value), value));
    }

    private String impactKey(int value, int min, int max) {
        int span = Math.max(1, max - min);
        if (value <= min + span / 3) {
            return "key.aoc.impact.low";
        }
        if (value <= min + span * 2 / 3) {
            return "key.aoc.impact.medium";
        }
        return "key.aoc.impact.high";
    }

    private String budgetImpactKey(int value) {
        if (value <= 8) return "key.aoc.impact.budget_low";
        if (value <= 32) return "key.aoc.impact.budget_medium";
        return "key.aoc.impact.budget_high";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
