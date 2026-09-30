package androidoptimizationcore;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.Name("Advanced Optimization Core")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public final class AOCCorePlugin implements IFMLLoadingPlugin {
    @Override public String[] getASMTransformerClass() { return new String[] { "androidoptimizationcore.AOCTransformer" }; }
    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public void injectData(Map<String, Object> data) { }
    @Override public String getAccessTransformerClass() { return null; }
}
