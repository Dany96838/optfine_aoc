package androidoptimizationcore;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiSlider;

import java.io.IOException;

/**
 * Player-only AOC settings.
 *
 * This screen intentionally separates player visibility from the generic
 * entity settings. Player culling is disabled by default and the name-tag
 * opacity starts at vanilla-equivalent 100%.
 */
public final class AOCPlayerGui extends GuiScreen implements GuiSlider.ISlider {
    private final GuiScreen parent;

    public AOCPlayerGui(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        final int bw = 300;
        final int bh = 20;
        final int x = width / 2 - bw / 2;
        final int y = 48;
        final int step = 30;

        buttonList.add(new GuiButton(1, x, y, bw, bh,
                toggle("key.aoc.player_culling", AOCConfig.playerCulling)));

        buttonList.add(new GuiSlider(2, x, y + step, bw, bh,
                tr("key.aoc.player_distance") + ": ",
                " " + tr("key.aoc.unit.blocks"),
                8.0D, 640.0D, AOCConfig.playerDistance,
                false, true, this));

        buttonList.add(new GuiButton(3, x, y + step * 2, bw, bh,
                toggle("key.aoc.player_name_opacity_enabled",
                        AOCConfig.playerNameOpacityEnabled)));

        buttonList.add(new GuiSlider(4, x, y + step * 3, bw, bh,
                tr("key.aoc.player_name_opacity") + ": ",
                "%",
                0.0D, 100.0D, AOCConfig.playerNameOpacity * 100.0D,
                false, true, this));

        buttonList.add(new GuiButton(5, x, y + step * 4, bw, bh,
                tr("key.aoc.reset")));

        buttonList.add(new GuiButton(6, x, y + step * 5, bw, bh,
                tr("key.aoc.back")));
    }

    private String toggle(String key, boolean value) {
        return tr(key) + ": " + tr(value ? "key.aoc.on" : "key.aoc.off");
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 1:
                AOCConfig.playerCulling = !AOCConfig.playerCulling;
                break;
            case 3:
                AOCConfig.playerNameOpacityEnabled =
                        !AOCConfig.playerNameOpacityEnabled;
                break;
            case 5:
                AOCConfig.playerCulling = false;
                AOCConfig.playerDistance = 32;
                AOCConfig.playerNameOpacityEnabled = true;
                AOCConfig.playerNameOpacity = 1.0F;
                break;
            case 6:
                AOCConfig.save();
                mc.displayGuiScreen(parent);
                return;
            default:
                return;
        }

        AOCConfig.applyToRuntime();
        AOCConfig.save();
        initGui();
    }

    @Override
    public void onChangeSliderValue(GuiSlider slider) {
        try {
            if (slider.id == 2) {
                AOCConfig.playerDistance =
                        Math.max(8, Math.min(640, slider.getValueInt()));
            } else if (slider.id == 4) {
                int percent = Math.max(0, Math.min(100, slider.getValueInt()));
                AOCConfig.playerNameOpacity = percent / 100.0F;
            } else {
                return;
            }
            AOCConfig.applyToRuntime();
            AOCConfig.save();
        } catch (Throwable ignored) {
        }
    }

    private String tr(String key) {
        String value = I18n.format(key);
        if (!value.equals(key)) return value;

        boolean pt = Minecraft.getMinecraft().gameSettings.language != null
                && Minecraft.getMinecraft().gameSettings.language
                .toLowerCase().startsWith("pt");

        if (pt) {
            if ("key.aoc.title".equals(key)) return "Android Optimization Core";
            if ("key.aoc.player_culling".equals(key))
                return "Otimização de Players";
            if ("key.aoc.player_distance".equals(key))
                return "Distância dos Players";
            if ("key.aoc.player_name_opacity_enabled".equals(key))
                return "Opacidade do Nick";
            if ("key.aoc.player_name_opacity".equals(key))
                return "Opacidade do Nick";
            if ("key.aoc.unit.blocks".equals(key)) return "blocos";
            if ("key.aoc.on".equals(key)) return "Ligado";
            if ("key.aoc.off".equals(key)) return "Desligado";
            if ("key.aoc.reset".equals(key)) return "Restaurar Padrões";
            if ("key.aoc.back".equals(key)) return "Voltar";
        } else {
            if ("key.aoc.title".equals(key)) return "Android Optimization Core";
            if ("key.aoc.player_culling".equals(key))
                return "Player Optimization";
            if ("key.aoc.player_distance".equals(key))
                return "Player Distance";
            if ("key.aoc.player_name_opacity_enabled".equals(key))
                return "Name Tag Opacity";
            if ("key.aoc.player_name_opacity".equals(key))
                return "Name Tag Opacity";
            if ("key.aoc.unit.blocks".equals(key)) return "blocks";
            if ("key.aoc.on".equals(key)) return "On";
            if ("key.aoc.off".equals(key)) return "Off";
            if ("key.aoc.reset".equals(key)) return "Reset Defaults";
            if ("key.aoc.back".equals(key)) return "Back";
        }
        return key;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, tr("key.aoc.title"),
                width / 2, 16, 0xFFFFFF);

        boolean pt = Minecraft.getMinecraft().gameSettings.language != null
                && Minecraft.getMinecraft().gameSettings.language
                .toLowerCase().startsWith("pt");
        drawCenteredString(fontRenderer,
                pt ? "Configurações exclusivas de Players"
                   : "Player-only Settings",
                width / 2, 30, 0xA0A0A0);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
