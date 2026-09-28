package androidoptimizationcore;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class AOCGui extends GuiScreen {
    private final GuiScreen parent;
    private final List<String> tooltip = new ArrayList<String>();
    private GuiButton entity, tile, occlusion, animations, entityDist, tileDist, budget, interval;

    public AOCGui(GuiScreen parent) { this.parent = parent; }

    @Override public void initGui() {
        buttonList.clear();
        int x = width/2 - 120;
        entity = add(new GuiButton(1,x,45,240,20,text("key.aoc.entity",AOCConfig.entityCulling)));
        tile = add(new GuiButton(2,x,70,240,20,text("key.aoc.tile",AOCConfig.tileEntityCulling)));
        occlusion = add(new GuiButton(3,x,95,240,20,text("key.aoc.occlusion",AOCConfig.occlusionCulling)));
        animations = add(new GuiButton(4,x,120,240,20,text("key.aoc.animations",AOCConfig.animatedTextures)));
        entityDist = add(new GuiButton(5,x,145,240,20,I18n.format("key.aoc.entity_distance")+": "+AOCConfig.entityDistance));
        tileDist = add(new GuiButton(6,x,170,240,20,I18n.format("key.aoc.tile_distance")+": "+AOCConfig.tileEntityDistance));
        budget = add(new GuiButton(7,x,195,240,20,I18n.format("key.aoc.occlusion_budget")+": "+AOCConfig.occlusionBudget));
        interval = add(new GuiButton(8,x,220,240,20,I18n.format("key.aoc.animation_interval")+": "+AOCConfig.animationInterval));
        add(new GuiButton(9,x,250,240,20,I18n.format("key.aoc.done")));
    }

    private GuiButton add(GuiButton b) { buttonList.add(b); return b; }
    private String text(String key, boolean value) { return I18n.format(key)+": "+I18n.format(value?"key.aoc.on":"key.aoc.off"); }

    @Override protected void actionPerformed(GuiButton b) throws IOException {
        switch (b.id) {
            case 1: AOCConfig.entityCulling=!AOCConfig.entityCulling; break;
            case 2: AOCConfig.tileEntityCulling=!AOCConfig.tileEntityCulling; break;
            case 3: AOCConfig.occlusionCulling=!AOCConfig.occlusionCulling; break;
            case 4: AOCConfig.animatedTextures=!AOCConfig.animatedTextures; break;
            case 5: AOCConfig.entityDistance=next(AOCConfig.entityDistance); break;
            case 6: AOCConfig.tileEntityDistance=next(AOCConfig.tileEntityDistance); break;
            case 7: AOCConfig.occlusionBudget=nextBudget(AOCConfig.occlusionBudget); break;
            case 8: AOCConfig.animationInterval=nextInterval(AOCConfig.animationInterval); break;
            case 9: AOCConfig.save(); mc.displayGuiScreen(parent); return;
        }
        AOCConfig.save(); initGui();
    }

    private int next(int v) { int[] a={8,16,32,64,128,256}; for(int i=0;i<a.length;i++) if(a[i]==v) return a[(i+1)%a.length]; return 32; }
    private int nextBudget(int v) { int[] a={0,8,16,24,32,48,64,96}; for(int i=0;i<a.length;i++) if(a[i]==v) return a[(i+1)%a.length]; return 24; }
    private int nextInterval(int v) { return v>=4?1:v+1; }

    @Override public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, I18n.format("key.aoc.open"), width/2, 20, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        tooltip.clear();
        GuiButton hovered = null;
        for(Object o: buttonList) { GuiButton b=(GuiButton)o; if(b.isMouseOver()) { hovered=b; break; } }
        if (hovered != null) {
            String key = hovered.id==1?"key.aoc.tooltip.entity":hovered.id==2?"key.aoc.tooltip.tile":hovered.id==3?"key.aoc.tooltip.occlusion":hovered.id==4?"key.aoc.tooltip.animations":hovered.id>=5&&hovered.id<=6?"key.aoc.tooltip.distance":hovered.id==7?"key.aoc.tooltip.budget":"key.aoc.tooltip.interval";
            tooltip.add(I18n.format(key));
            GuiUtils.drawHoveringText(tooltip, mouseX, mouseY, width, height, 240, fontRenderer);
        }
    }

    @Override public boolean doesGuiPauseGame() { return false; }
}
