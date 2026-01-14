package name.atlasclient;

import name.atlasclient.script.ExampleScript;
import name.atlasclient.script.ScriptManager;
import name.atlasclient.ui.AtlasMainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtlasClient implements ClientModInitializer {
    public static final String MOD_ID = "atlas-client";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding ATLAS_TOGGLE;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Atlas Client initializing (client-only).");

        // NOTE: Window icon code removed because 1.21.5 API no longer matches:
        // - Identifier constructor is private
        // - Window#setIcon no longer accepts InputStreams

        // Register scripts (placeholder; add more later)
        ScriptManager.register(new ExampleScript());
        ScriptManager.register(new name.atlasclient.script.mining.MithrilMiningScript());
        ScriptManager.register(new name.atlasclient.script.mining.OreMiningScript());

        // Keybind: Insert toggles Atlas (open when idle, stop when running)
        ATLAS_TOGGLE = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.atlasclient.toggle",
                GLFW.GLFW_KEY_INSERT,
                "category.atlasclient"
        ));

        // Insert behavior:
        // - If any script is running: stop all scripts (and close UI if open)
        // - Else: open Atlas UI
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {

            // Tick enabled scripts
            for (var s : ScriptManager.all()) {
                if (s.isEnabled()) {
                    s.onTick(mc);
                }
            }

            while (ATLAS_TOGGLE.wasPressed()) {
                if (ScriptManager.anyRunning()) {
                    ScriptManager.stopAll();
                    if (mc.currentScreen instanceof AtlasMainScreen) {
                        mc.setScreen(null);
                    }
                } else {
                    mc.setScreen(new AtlasMainScreen(null));
                }
            }
        });

        // /atlas command opens the menu
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("atlas")
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        mc.execute(() -> mc.setScreen(new AtlasMainScreen(null)));
                        return 1;
                    }));
        });
    }
}
