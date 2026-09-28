package androidoptimizationcore;

import net.minecraftforge.fml.common.FMLLog;
import java.lang.reflect.Field;

public final class OptiFineCompat {
    public static final String REQUIRED = "OptiFine_1.12.2_HD_U_G5";
    private static boolean validated;
    private OptiFineCompat() {}

    public static void requireG5() {
        if (validated) return;
        try {
            Class<?> config = Class.forName("Config", false, OptiFineCompat.class.getClassLoader());
            Field version = config.getField("VERSION");
            String value = String.valueOf(version.get(null));
            if (!REQUIRED.equals(value)) throw new IllegalStateException("Unsupported OptiFine: " + value + "; required " + REQUIRED);
            validated = true;
            FMLLog.log.info("[AOC] OptiFine validated: {}", value);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("OptiFine HD U G5 is required.", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to validate OptiFine HD U G5.", e);
        }
    }
}
