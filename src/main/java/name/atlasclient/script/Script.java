package name.atlasclient.script;

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
}
