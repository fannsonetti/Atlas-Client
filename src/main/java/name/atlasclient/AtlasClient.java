package name.atlasclient;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import name.atlasclient.script.ExampleScript;
import name.atlasclient.script.Script;
import name.atlasclient.script.ScriptManager;
import name.atlasclient.script.misc.PathfindScript;
import name.atlasclient.ui.AtlasMainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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

        // Register scripts (single instances)
        ExampleScript example = new ExampleScript();

        name.atlasclient.script.mining.MithrilMiningScript mithril = new name.atlasclient.script.mining.MithrilMiningScript();
        name.atlasclient.script.mining.CommisionScript commision = new name.atlasclient.script.mining.CommisionScript();
        name.atlasclient.script.mining.OreMiningScript ore = new name.atlasclient.script.mining.OreMiningScript();
        name.atlasclient.script.combat.GraveyardScr
        name.atlasclient.script.misc.PathfindScript pathfind = new name.atlasclient.script.misc.PathfindScript();
        name.atlasclient.script.intermediary.DrillRefueler drillRefueler = new name.atlasclient.script.intermediary.DrillRefueler();

        // Inject so CommisionScript can call the real instances that are registered
        commision.setDependencies(pathfind, mithril);

        // Register them
        ScriptManager.register(example);
        ScriptManager.register(commision);
        ScriptManager.register(mithril);
        ScriptManager.register(ore);
        ScriptManager.register(pathfind);
        ScriptManager.register(drillRefueler);


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
                    // Use current screen as parent when possible
                    mc.setScreen(new AtlasMainScreen(mc.currentScreen));
                }
            }
        });

        // Commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // /atlas opens the menu
            dispatcher.register(ClientCommandManager.literal("atlas")
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        LOGGER.info("/atlas invoked");

                        mc.execute(() -> {
                            try {
                                mc.setScreen(new AtlasMainScreen(mc.currentScreen));
                                LOGGER.info("AtlasMainScreen opened.");
                            } catch (Throwable t) {
                                LOGGER.error("Failed to open AtlasMainScreen", t);
                                if (mc.player != null) {
                                    mc.player.sendMessage(Text.literal("Failed to open Atlas UI (see latest.log)."), false);
                                }
                            }
                        });

                        return 1;
                    })
            );

            // /walkto <x> <y> <z>
            dispatcher.register(ClientCommandManager.literal("walkto")
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(ctx -> {
                                                int x = IntegerArgumentType.getInteger(ctx, "x");
                                                int y = IntegerArgumentType.getInteger(ctx, "y");
                                                int z = IntegerArgumentType.getInteger(ctx, "z");

                                                MinecraftClient mc = MinecraftClient.getInstance();
                                                mc.execute(() -> {
                                                    if (mc.player == null) return;

                                                    PathfindScript p = findPathfindScript();
                                                    if (p == null) {
                                                        mc.player.sendMessage(Text.literal("PathfindScript not registered/found."), false);
                                                        return;
                                                    }

                                                    // Ensure enabled (and run lifecycle hook so ACTIVE_INSTANCE is set and render hook is registered)
                                                    if (!p.isEnabled()) {
                                                        p.setEnabled(true);
                                                        try {
                                                            p.onEnable(mc);
                                                        } catch (Throwable t) {
                                                            LOGGER.error("Error enabling PathfindScript", t);
                                                            mc.player.sendMessage(Text.literal("Failed to enable PathfindScript (see latest.log)."), false);
                                                            return;
                                                        }
                                                    }

                                                    BlockPos target = new BlockPos(x, y, z);
                                                    try {
                                                        p.navigateTo(target);
                                                        mc.player.sendMessage(Text.literal("Walking to " + x + " " + y + " " + z + "..."), false);
                                                    } catch (Throwable t) {
                                                        LOGGER.error("Error starting navigation to {}", target, t);
                                                        mc.player.sendMessage(Text.literal("Failed to start pathfinding (see latest.log)."), false);
                                                    }
                                                });

                                                return 1;
                                            })
                                    )
                            )
                    )
            );
        });
    }

    private static PathfindScript findPathfindScript() {
        for (Script s : ScriptManager.all()) {
            if (s instanceof PathfindScript p) return p;
        }
        return null;
    }
}
