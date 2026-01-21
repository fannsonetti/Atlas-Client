package name.atlasclient.script;

import name.atlasclient.ui.HudPanel;
import net.minecraft.client.MinecraftClient;

public interface Script {
    String id();
    String displayName();
    String description();
    default String category() { return "Misc"; }

    boolean isEnabled();
    void setEnabled(boolean enabled);

    default void onEnable(MinecraftClient client) {}
    default void onDisable() {}
    default void onTick(MinecraftClient client) {}

    /**
     * Optional HUD panel for this script.
     * Return null to draw nothing.
     */
    default HudPanel buildHudPanel() {
        return null;
    }
}
