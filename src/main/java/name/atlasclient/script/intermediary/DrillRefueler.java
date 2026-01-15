package name.atlasclient.script.intermediary;

import name.atlasclient.script.Script;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;

/**
 * Drill Refueler
 *
 * Changes vs earlier version:
 * - Goblin Egg is shift-clicked into the forge UI (after drill is inserted).
 * - Adds deliberate menu delays so GUI clicks do not happen too fast.
 *
 * You MUST adjust:
 * - FORGE_NPC_EXACT_NAME
 * - DRILL_ITEM_NAME
 * - FUEL_ITEM_NAME
 * - Bazaar / Forge label strings (if your GUI uses different names)
 */
public class DrillRefueler implements Script {





    /** Where you want to walk after /warp forge. */
    private static final BlockPos FORGE_TARGET_POS = new BlockPos(-7, 145, -20);

    /** Radius to search for NPC/entities by name. */
    private static final double ENTITY_SEARCH_RADIUS = 6.0;

    /** Exact NPC name to open forge UI (must match getName().getString()). */
    private static final String FORGE_NPC_EXACT_NAME = "Jotraeline Greatforge";

    /** Drill item id (Registries.ITEM id), e.g. "modid:drill". */
    private static final String DRILL_ITEM_NAME = "Drill";

    /** Fuel item id (Registries.ITEM id), e.g. "modid:goblin_egg". */
    private static final String FUEL_ITEM_NAME = "Goblin Egg";


    private static final String BZ_CATEGORY_MINING = "Mining";
    private static final String BZ_SUBCATEGORY_GOBLIN_EGGS = "Goblin";
    private static final String BZ_PRODUCT_GOBLIN_EGG = "Goblin Egg";
    private static final String BZ_BUTTON_INSTANT_BUY = "Buy Instantly";
    private static final String BZ_BUTTON_BUY_ONE = "Buy only one!";


    private static final String FORGE_UI_ANVIL = "Anvil";
    private static final String FORGE_UI_INSERT = "Insert";





    /** Delay after each GUI click to avoid racing the server UI updates. */
    private static final int MENU_CLICK_DELAY_TICKS = 10;

    /** Delay after opening Bazaar / Forge UI for initial population. */
    private static final int MENU_OPEN_DELAY_TICKS = 12;

    /** Delay after warp command to let the server move you before pathing. */
    private static final int WARP_DELAY_TICKS = 40;





    private enum State {
        WARP_DELAY,
        WALK_TO_FORGE_TARGET,

        OPEN_BZ,
        SELECT_MINING,
        SELECT_GOBLIN_EGGS,
        SELECT_GOBLIN_EGG,
        BUY_INSTANTLY,
        BUY_ONE,
        CLOSE_BZ,

        INTERACT_FORGE_NPC,
        WAIT_FOR_FORGE_UI,

        INSERT_DRILL,
        INSERT_EGG,
        CLICK_DRILL_ANVIL,
        CLOSE_FINAL,
        DONE
    }

    private boolean enabled = false;
    private State state = State.DONE;

    /** Simple tick cooldown. */
    private int waitTicks = 0;

    /** Timeout safety for each state. */
    private int stateTimeoutTicks = 0;

    /** Pathfinding engine. */






    @Override
    public String id() {
        return "drill_refueler";
    }

    @Override
    public String displayName() {
        return "Drill Refueler";
    }

    @Override
    public String description() {
        return "Walks to the forge and refuels a drill by buying Goblin Eggs on /bz, then inserting the drill and fuel in the forge UI.";
    }

    @Override
    public String category() {
        return "Intermediary";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * IMPORTANT:
     * Keep setEnabled as a pure setter so ScriptManager lifecycle does not double-trigger onEnable().
     */
    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void onEnable(MinecraftClient mc) {
        enabled = true;
        // Defensive: if Minecraft isn't fully available yet, just mark enabled and let onTick drive.
        if (mc == null || mc.player == null) {
            state = State.WARP_DELAY;
            setState(State.WARP_DELAY, 20 * 30);
            return;
        }

        waitTicks = 0;
        setState(State.WARP_DELAY, 20 * 30);
        issueChatCommand(mc, "/warp forge");
        setWait(WARP_DELAY_TICKS);
    }

    @Override
    public void onDisable() {
        enabled = false;

        waitTicks = 0;
        stateTimeoutTicks = 0;
        state = State.DONE;
    }

    @Override
    public void onTick(MinecraftClient mc) {
        if (!enabled) return;
        if (mc == null || mc.player == null || mc.world == null) return;

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (stateTimeoutTicks > 0) {
            stateTimeoutTicks--;
            if (stateTimeoutTicks == 0) {
                state = State.DONE;
                return;
            }
        }

        switch (state) {

            case WARP_DELAY -> {
                setState(State.WALK_TO_FORGE_TARGET, 20 * 30);
            }

            case WALK_TO_FORGE_TARGET -> {
                if (!mc.player.getBlockPos().isWithinDistance(FORGE_TARGET_POS, 3.0)) {
                    // If you have a pathing system, invoke it here. As a lightweight fallback,
                    // we simply wait and keep checking proximity.
                    setWait(10);
                    return;
                }

                issueChatCommand(mc, "/bz");
                setWait(MENU_OPEN_DELAY_TICKS);
                setState(State.OPEN_BZ, 20 * 20);
            }

            case OPEN_BZ -> {
                if (isHandledScreen(mc)) {
                    setWait(MENU_OPEN_DELAY_TICKS);
                    setState(State.SELECT_MINING, 20 * 20);
                    return;
                }


                issueChatCommand(mc, "/bz");
                setWait(MENU_OPEN_DELAY_TICKS);
            }

            case SELECT_MINING -> {
                if (!isHandledScreen(mc)) {
                    setState(State.OPEN_BZ, 20 * 20);
                    return;
                }

                if (clickSlotWithNameContains(mc, BZ_CATEGORY_MINING)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.SELECT_GOBLIN_EGGS, 20 * 20);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case SELECT_GOBLIN_EGGS -> {
                if (!isHandledScreen(mc)) {
                    setState(State.OPEN_BZ, 20 * 20);
                    return;
                }

                if (clickSlotWithNameContains(mc, BZ_SUBCATEGORY_GOBLIN_EGGS)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.SELECT_GOBLIN_EGG, 20 * 20);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case SELECT_GOBLIN_EGG -> {
                if (!isHandledScreen(mc)) {
                    setState(State.OPEN_BZ, 20 * 20);
                    return;
                }

                if (clickSlotWithNameContains(mc, FUEL_ITEM_NAME) || clickSlotWithNameContains(mc, BZ_PRODUCT_GOBLIN_EGG)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.BUY_INSTANTLY, 20 * 20);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case BUY_INSTANTLY -> {
                if (!isHandledScreen(mc)) {
                    setState(State.OPEN_BZ, 20 * 20);
                    return;
                }

                if (clickSlotWithNameContains(mc, BZ_BUTTON_INSTANT_BUY)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.BUY_ONE, 20 * 20);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case BUY_ONE -> {
                if (!isHandledScreen(mc)) {
                    setState(State.OPEN_BZ, 20 * 20);
                    return;
                }

                if (clickSlotWithNameContains(mc, BZ_BUTTON_BUY_ONE)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.CLOSE_BZ, 20 * 10);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case CLOSE_BZ -> {
                closeScreen(mc);
                setWait(MENU_OPEN_DELAY_TICKS);
                setState(State.INTERACT_FORGE_NPC, 20 * 30);
            }

            case INTERACT_FORGE_NPC -> {
                Entity npc = findNearestEntityByExactName(mc, FORGE_NPC_EXACT_NAME, ENTITY_SEARCH_RADIUS);
                if (npc == null) {
                    setWait(20);
                    return;
                }

                mc.interactionManager.interactEntity(mc.player, npc, Hand.MAIN_HAND);
                setWait(MENU_OPEN_DELAY_TICKS);
                setState(State.WAIT_FOR_FORGE_UI, 20 * 20);
            }

            case WAIT_FOR_FORGE_UI -> {
                if (isHandledScreen(mc)) {
                    setWait(MENU_OPEN_DELAY_TICKS);
                    setState(State.INSERT_DRILL, 20 * 20);
                    return;
                }


                setState(State.INTERACT_FORGE_NPC, 20 * 30);
                setWait(10);
            }

            case INSERT_DRILL -> {
                if (!isHandledScreen(mc)) {
                    setState(State.INTERACT_FORGE_NPC, 20 * 30);
                    return;
                }

                if (quickMoveFromPlayerInvToContainerByNameContains(mc, DRILL_ITEM_NAME)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.INSERT_EGG, 20 * 20);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case INSERT_EGG -> {
                if (!isHandledScreen(mc)) {
                    setState(State.INTERACT_FORGE_NPC, 20 * 30);
                    return;
                }


                if (quickMoveFromPlayerInvToContainerByNameContains(mc, FUEL_ITEM_NAME)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.CLICK_DRILL_ANVIL, 20 * 20);
                } else {


                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.CLICK_DRILL_ANVIL, 20 * 20);
                }
            }

            case CLICK_DRILL_ANVIL -> {
                if (!isHandledScreen(mc)) {
                    setState(State.INTERACT_FORGE_NPC, 20 * 30);
                    return;
                }

                if (clickSlotWithNameContains(mc, FORGE_UI_ANVIL) || clickSlotWithNameContains(mc, FORGE_UI_INSERT)) {
                    setWait(MENU_CLICK_DELAY_TICKS);
                    setState(State.CLOSE_FINAL, 20 * 10);
                } else {
                    setWait(MENU_CLICK_DELAY_TICKS);
                }
            }

            case CLOSE_FINAL -> {
                closeScreen(mc);
                setWait(MENU_OPEN_DELAY_TICKS);
                setState(State.DONE, 0);
            }

            case DONE -> {

            }
        }
    }





    private void setState(State newState, int timeoutTicks) {
        this.state = newState;
        this.stateTimeoutTicks = timeoutTicks;
    }

    private void setWait(int ticks) {
        this.waitTicks = Math.max(this.waitTicks, ticks);
    }





    private void issueChatCommand(MinecraftClient mc, String command) {
        if (mc == null || mc.player == null) return;

        String raw = command == null ? "" : command.trim();
        if (raw.isEmpty()) return;

        String withSlash = raw.startsWith("/") ? raw : ("/" + raw);
        String noSlash = withSlash.substring(1);

        if (mc.player.networkHandler == null) return;


        try {
            mc.player.networkHandler.sendChatCommand(noSlash);
            return;
        } catch (Throwable ignored) { }


        try {
            mc.player.networkHandler.getClass()
                    .getMethod("sendCommand", String.class)
                    .invoke(mc.player.networkHandler, noSlash);
            return;
        } catch (Throwable ignored) { }


        try {
            mc.player.networkHandler.sendChatMessage(withSlash);
        } catch (Throwable ignored) { }
    }





    private boolean isHandledScreen(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?>;
    }

    private void closeScreen(MinecraftClient mc) {
        if (mc.player != null) mc.player.closeHandledScreen();
    }

    private boolean clickSlotWithNameContains(MinecraftClient mc, String contains) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return false;
        if (mc.player == null) return false;

        ScreenHandler handler = handled.getScreenHandler();
        if (handler == null) return false;

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot == null) continue;

            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;

            String name = stack.getName().getString();
            if (name != null && name.contains(contains)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                return true;
            }
        }
        return false;
    }

    /**
     * Shift-click an item from the player inventory/hotbar into the open container by item id.
     */
    private boolean quickMoveFromPlayerInvToContainerByNameContains(MinecraftClient mc, String nameContains) {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return false;
        if (mc.player == null || nameContains == null || nameContains.isBlank()) return false;

        ScreenHandler handler = handled.getScreenHandler();
        if (handler == null) return false;

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot == null) continue;


            if (slot.inventory != mc.player.getInventory()) continue;

            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;

            String displayName = stack.getName().getString();
            if (displayName != null && displayName.contains(nameContains)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                return true;
            }
        }
        return false;
    }





    private Entity findNearestEntityByExactName(MinecraftClient mc, String exactName, double radius) {
        if (mc.world == null || mc.player == null) return null;

        List<Entity> entities = mc.world.getEntitiesByClass(
                Entity.class,
                mc.player.getBoundingBox().expand(radius),
                e -> e != null && e.getName() != null && e.getName().getString().equals(exactName)
        );

        return entities.stream()
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }
}