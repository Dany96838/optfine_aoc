package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.client.config.GuiUtils;

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
                tr("key.aoc.entity_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.entityDistance,
                false, true, this));

        buttonList.add(new GuiButton(2, left, y + step, bw, bh,
                toggle("key.aoc.item", AOCConfig.itemCulling)));
        buttonList.add(new GuiSlider(11, right, y + step, bw, bh,
                tr("key.aoc.item_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.itemDistance,
                false, true, this));

        buttonList.add(new GuiButton(3, left, y + step * 2, bw, bh,
                toggle("key.aoc.tile", AOCConfig.tileEntityCulling)));
        buttonList.add(new GuiSlider(12, right, y + step * 2, bw, bh,
                tr("key.aoc.tile_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.tileEntityDistance,
                false, true, this));

        buttonList.add(new GuiButton(4, left, y + step * 3, bw, bh,
                toggle("key.aoc.particle", AOCConfig.particleCulling)));
        buttonList.add(new GuiSlider(13, right, y + step * 3, bw, bh,
                tr("key.aoc.particle_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.particleDistance,
                false, true, this));

        buttonList.add(new GuiButton(5, left, y + step * 4, bw, bh,
                toggle("key.aoc.effect", AOCConfig.effectCulling)));
        buttonList.add(new GuiSlider(14, right, y + step * 4, bw, bh,
                tr("key.aoc.effect_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 160.0D, AOCConfig.effectDistance,
                false, true, this));

        buttonList.add(new GuiButton(6, left, y + step * 5, bw, bh,
                toggle("key.aoc.occlusion", AOCConfig.occlusionCulling)));
        buttonList.add(new GuiSlider(15, right, y + step * 5, bw, bh,
                tr("key.aoc.occlusion_budget") + ": ",
                " " + tr("key.aoc.unit.checks"),
                0.0D, 64.0D, AOCConfig.occlusionBudget,
                false, true, this));

        buttonList.add(new GuiButton(8, left, y + step * 6, bw, bh,
                tr("key.aoc.reset")));
        buttonList.add(new GuiButton(9, right, y + step * 6, bw, bh,
                tr("key.aoc.done")));
    }

    private String toggle(String key, boolean value) {
        return tr(key) + ": "
                + tr(value ? "key.aoc.on" : "key.aoc.off");
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

    private String tr(String key) {
        String value = I18n.format(key);
        if (!value.equals(key)) return value;

        boolean pt = Minecraft.getMinecraft().gameSettings.language != null
                && Minecraft.getMinecraft().gameSettings.language.toLowerCase().startsWith("pt");

        if (pt) {
            if ("key.aoc.title".equals(key)) return "Android Optimization Core";
            if ("key.aoc.subtitle".equals(key)) return "Otimização independente de renderização no cliente para Forge 1.12.2";
            if ("key.aoc.done".equals(key)) return "Concluído";
            if ("key.aoc.reset".equals(key)) return "Restaurar Padrões";
            if ("key.aoc.entity".equals(key)) return "Culling de Entidades";
            if ("key.aoc.item".equals(key)) return "Itens Dropados";
            if ("key.aoc.tile".equals(key)) return "Culling de TileEntities";
            if ("key.aoc.particle".equals(key)) return "Otimização de Partículas";
            if ("key.aoc.effect".equals(key)) return "Otimização de Efeitos Visuais";
            if ("key.aoc.occlusion".equals(key)) return "Culling por Oclusão";
            if ("key.aoc.on".equals(key)) return "Ligado";
            if ("key.aoc.off".equals(key)) return "Desligado";
            if ("key.aoc.entity_distance".equals(key)) return "Distância das Entidades";
            if ("key.aoc.item_distance".equals(key)) return "Distância dos Itens Dropados";
            if ("key.aoc.tile_distance".equals(key)) return "Distância dos TileEntities";
            if ("key.aoc.particle_distance".equals(key)) return "Distância das Partículas";
            if ("key.aoc.effect_distance".equals(key)) return "Distância dos Efeitos Visuais";
            if ("key.aoc.occlusion_budget".equals(key)) return "Verificações de Oclusão por Tick";
            if ("key.aoc.unit.blocks".equals(key)) return "blocos";
            if ("key.aoc.unit.checks".equals(key)) return "verificações";
            if ("key.aoc.tooltip.entity".equals(key)) return "Controla quando entidades podem deixar de ser renderizadas para reduzir o custo visual. As entidades continuam ativas.";
            if ("key.aoc.tooltip.item".equals(key)) return "Controla a renderização de itens dropados no mundo. Os itens continuam existindo e funcionando normalmente.";
            if ("key.aoc.tooltip.tile".equals(key)) return "Controla a renderização de TileEntities sem descarregá-los nem alterar seu funcionamento.";
            if ("key.aoc.tooltip.particle".equals(key)) return "Reduz a renderização de partículas distantes sem alterar a lógica que as cria.";
            if ("key.aoc.tooltip.effect".equals(key)) return "Controla efeitos visuais do cliente sem alterar o estado do jogo.";
            if ("key.aoc.tooltip.occlusion".equals(key)) return "Oculta a renderização quando blocos sólidos confirmam que o objeto está completamente oculto.";
            if ("key.aoc.tooltip.reset".equals(key)) return "Restaura os valores padrão das opções do AOC.";
            if ("key.aoc.tooltip.entity_distance".equals(key)) return "Define até que distância as entidades podem continuar sendo renderizadas pelo AOC.";
            if ("key.aoc.tooltip.item_distance".equals(key)) return "Define até que distância os itens dropados podem continuar sendo renderizados pelo AOC.";
            if ("key.aoc.tooltip.tile_distance".equals(key)) return "Define até que distância os TileEntities podem continuar sendo renderizados pelo AOC.";
            if ("key.aoc.tooltip.particle_distance".equals(key)) return "Define até que distância as partículas podem continuar sendo renderizadas pelo AOC.";
            if ("key.aoc.tooltip.effect_distance".equals(key)) return "Define até que distância os efeitos visuais podem continuar sendo renderizados pelo AOC.";
            if ("key.aoc.tooltip.budget".equals(key)) return "Define quantas verificações de oclusão o AOC pode fazer por tick. Mais verificações atualizam a oclusão mais rapidamente, mas usam mais CPU.";
            if ("key.aoc.impact.low".equals(key)) return "Impacto atual: baixo (%d). Menor distância reduz o trabalho de renderização e pode aumentar o desempenho.";
            if ("key.aoc.impact.medium".equals(key)) return "Impacto atual: médio (%d). Equilibra visibilidade e custo de renderização.";
            if ("key.aoc.impact.high".equals(key)) return "Impacto atual: alto (%d). Maior distância mantém mais objetos visíveis e pode aumentar o custo de renderização.";
            if ("key.aoc.impact.budget_low".equals(key)) return "Impacto atual: baixo (%d). Menos verificações usam menos CPU, mas a oclusão pode atualizar mais lentamente.";
            if ("key.aoc.impact.budget_medium".equals(key)) return "Impacto atual: médio (%d). Equilibra rapidez da oclusão e custo de CPU.";
            if ("key.aoc.impact.budget_high".equals(key)) return "Impacto atual: alto (%d). Mais verificações atualizam a oclusão mais rapidamente, com maior custo de CPU.";
        } else {
            if ("key.aoc.title".equals(key)) return "Android Optimization Core";
            if ("key.aoc.subtitle".equals(key)) return "Independent client-side rendering optimization for Forge 1.12.2";
            if ("key.aoc.done".equals(key)) return "Done";
            if ("key.aoc.reset".equals(key)) return "Reset Defaults";
            if ("key.aoc.entity".equals(key)) return "Entity Culling";
            if ("key.aoc.item".equals(key)) return "Dropped Items";
            if ("key.aoc.tile".equals(key)) return "TileEntity Culling";
            if ("key.aoc.particle".equals(key)) return "Particle Optimization";
            if ("key.aoc.effect".equals(key)) return "Visual Effects Optimization";
            if ("key.aoc.occlusion".equals(key)) return "Occlusion Culling";
            if ("key.aoc.on".equals(key)) return "On";
            if ("key.aoc.off".equals(key)) return "Off";
            if ("key.aoc.entity_distance".equals(key)) return "Entity Distance";
            if ("key.aoc.item_distance".equals(key)) return "Dropped Item Distance";
            if ("key.aoc.tile_distance".equals(key)) return "TileEntity Distance";
            if ("key.aoc.particle_distance".equals(key)) return "Particle Distance";
            if ("key.aoc.effect_distance".equals(key)) return "Visual Effects Distance";
            if ("key.aoc.occlusion_budget".equals(key)) return "Occlusion Checks per Tick";
            if ("key.aoc.unit.blocks".equals(key)) return "blocks";
            if ("key.aoc.unit.checks".equals(key)) return "checks";
            if ("key.aoc.tooltip.entity".equals(key)) return "Controls when entity rendering can be hidden to reduce visual rendering cost. Entities remain active.";
            if ("key.aoc.tooltip.item".equals(key)) return "Controls dropped-item rendering in the world. Items continue to exist and function normally.";
            if ("key.aoc.tooltip.tile".equals(key)) return "Controls TileEntity rendering without unloading them or changing their behavior.";
            if ("key.aoc.tooltip.particle".equals(key)) return "Reduces distant particle rendering without changing the logic that creates particles.";
            if ("key.aoc.tooltip.effect".equals(key)) return "Controls client-side visual effects without changing game state.";
            if ("key.aoc.tooltip.occlusion".equals(key)) return "Hides rendering when solid blocks confirm that the object is completely hidden.";
            if ("key.aoc.tooltip.reset".equals(key)) return "Restores the AOC default option values.";
            if ("key.aoc.tooltip.entity_distance".equals(key)) return "Sets how far entities can remain visible through the AOC rendering policy.";
            if ("key.aoc.tooltip.item_distance".equals(key)) return "Sets how far dropped items can remain visible through the AOC rendering policy.";
            if ("key.aoc.tooltip.tile_distance".equals(key)) return "Sets how far TileEntities can remain visible through the AOC rendering policy.";
            if ("key.aoc.tooltip.particle_distance".equals(key)) return "Sets how far particles can remain visible through the AOC rendering policy.";
            if ("key.aoc.tooltip.effect_distance".equals(key)) return "Sets how far visual effects can remain visible through the AOC rendering policy.";
            if ("key.aoc.tooltip.budget".equals(key)) return "Sets how many occlusion checks AOC may perform per tick. More checks update occlusion faster but use more CPU.";
            if ("key.aoc.impact.low".equals(key)) return "Current impact: low (%d). A shorter distance reduces rendering work and can improve performance.";
            if ("key.aoc.impact.medium".equals(key)) return "Current impact: medium (%d). Balances visibility and rendering cost.";
            if ("key.aoc.impact.high".equals(key)) return "Current impact: high (%d). A longer distance keeps more objects visible and can increase rendering cost.";
            if ("key.aoc.impact.budget_low".equals(key)) return "Current impact: low (%d). Fewer checks use less CPU, but occlusion can update more slowly.";
            if ("key.aoc.impact.budget_medium".equals(key)) return "Current impact: medium (%d). Balances occlusion responsiveness and CPU cost.";
            if ("key.aoc.impact.budget_high".equals(key)) return "Current impact: high (%d). More checks update occlusion faster with higher CPU cost.";
        }

        return key;
    }

    private String trf(String key, int value) {
        String translated = I18n.format(key, value);
        if (!translated.equals(key)) return translated;
        return String.format(tr(key), Integer.valueOf(value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(fontRenderer, tr("key.aoc.title"),
                width / 2, 16, 0xFFFFFF);
        drawCenteredString(fontRenderer, tr("key.aoc.subtitle"),
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
            GuiUtils.drawHoveringText(tooltip, mouseX, mouseY, width, height, 280, fontRenderer);
        }
    }

    private void addTooltipFor(GuiButton button) {
        switch (button.id) {
            case 1:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.entity"), 260));
                return;
            case 2:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.item"), 260));
                return;
            case 3:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.tile"), 260));
                return;
            case 4:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.particle"), 260));
                return;
            case 5:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.effect"), 260));
                return;
            case 6:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.occlusion"), 260));
                return;
            case 8:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.reset"), 260));
                return;
            case 9:
                tooltip.add(button.displayString);
                tooltip.addAll(fontRenderer.listFormattedStringToWidth(tr("key.aoc.tooltip.done"), 260));
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
        tooltip.add(tr(descriptionKey));
        tooltip.add(trf(impactKey(value, 8, max), value));
    }

    private void addBudgetTooltip(int value) {
        tooltip.add(tr("key.aoc.tooltip.budget"));
        tooltip.add(trf(budgetImpactKey(value), value));
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
