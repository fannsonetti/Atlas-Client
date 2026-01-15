package name.atlasclient.script.mining;

import name.atlasclient.script.Script;
import name.atlasclient.script.misc.PathfindScript;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CommisionScript (EMISSARY-DRIVEN)
 *
 * Flow:
 * 1) Pathfind to nearest emissary position
 * 2) Interact with nearest emissary entity to open GUI
 * 3) Find 3 writable books named "Commision #1/#2/#3" and parse lore line like "Royal Mines Titanium"
 * 4) Click a book to select/start
 * 5) Pathfind to area anchor (based on lore)
 * 6) Enable mithril miner and wait for chat: "<AREA> <MATERIAL> Commission Complete!"
 * 7) Return to emissary, open GUI, click matching book again
 */
public final class CommisionScript implements Script {

    // -------------------- Script interface (matches your repo) --------------------
    @Override public String id() { return "commision"; }
    @Override public String displayName() { return "Commision"; }
    @Override public String description() {
        return "Uses emissary books (GUI lore) to select commissions and completes them automatically.";
    }
    @Override public String category() { return "Mining"; }

    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // -------------------- Debug --------------------
    private static final boolean DEBUG = true;

    private static void dbg(MinecraftClient client, String msg) {
        if (!DEBUG) return;
        try {
            if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal("[Commision][DBG] " + msg), false);
            }
        } catch (Throwable ignored) {}
    }

    // -------------------- Dependencies --------------------
    private PathfindScript pathfind;
    private MithrilMiningScript mithrilMiner;

    public void setDependencies(PathfindScript pathfind, MithrilMiningScript mithrilMiner) {
        this.pathfind = pathfind;
        this.mithrilMiner = mithrilMiner;
    }

    // -------------------- Emissary positions (copied from your original script) --------------------
    private static final List<Vec3d> EMISSARY_ANCHORS = List.of(
            new Vec3d(170.5, 150, 32.5),
            new Vec3d(43.5, 135, 21.5),
            new Vec3d(58.5, 198, -11.5),
            new Vec3d(-75.5, 153, -11.5),
            new Vec3d(-131.5, 174, -51.5)
    );

    // -------------------- Area anchors (same as your original mining anchors) --------------------
    private static final class AreaDef {
        final String areaName;
        final Vec3d target;
        AreaDef(String areaName, Vec3d target) { this.areaName = areaName; this.target = target; }
    }

    private static final List<AreaDef> AREAS = List.of(
            new AreaDef("Royal Mines", new Vec3d(165.5, 162, 17.5)),
            new AreaDef("Cliffside Veins", new Vec3d(11.5, 128, 37.5)),
            new AreaDef("Rampart's Quarry", new Vec3d(-118.5, 151, -34.5))
    );

    private static AreaDef findArea(String areaName) {
        if (areaName == null) return null;
        for (AreaDef d : AREAS) {
            if (d.areaName.equalsIgnoreCase(areaName.trim())) return d;
        }
        return null;
    }

    // -------------------- Books --------------------
    private static final String BOOK1 = "Commision #1";
    private static final String BOOK2 = "Commision #2";
    private static final String BOOK3 = "Commision #3";

    private static boolean isCommissionBookName(String n) {
        if (n == null) return false;
        return n.equalsIgnoreCase(BOOK1) || n.equalsIgnoreCase(BOOK2) || n.equalsIgnoreCase(BOOK3);
    }

    // completion: "<AREA> <MATERIAL> Commission Complete!"
    private static final Pattern COMPLETE_LINE =
            Pattern.compile("^\\s*(.+?)\\s+commission\\s+complete!\\s*$", Pattern.CASE_INSENSITIVE);

    // -------------------- State machine --------------------
    private enum State {
        SEEK_EMISSARY_ANCHOR,
        PATH_TO_EMISSARY_ANCHOR,
        OPEN_EMISSARY_GUI,
        READ_BOOKS_AND_SELECT,
        PATH_TO_AREA,
        START_MINING,
        WAIT_FOR_COMPLETE_CHAT,
        RETURN_TO_EMISSARY,
        CLAIM_BOOK
    }

    private State state = State.SEEK_EMISSARY_ANCHOR;

    private Vec3d emissaryAnchor = null;
    private Vec3d lastEmissaryPos = null;
    private Entity emissaryEntity = null;

    private EmissaryCommission active = null;
    private int lastChatSeen = 0;

    private static final class EmissaryCommission {
        final String bookName;     // Commision #1/2/3
        final int slotIndex;       // slot index in handler
        final String area;         // Royal Mines
        final String material;     // Titanium

        EmissaryCommission(String bookName, int slotIndex, String area, String material) {
            this.bookName = bookName;
            this.slotIndex = slotIndex;
            this.area = area;
            this.material = material;
        }

        String keyUpper() {
            return (area + " " + material).toUpperCase(Locale.ROOT);
        }

        @Override public String toString() {
            return "EmissaryCommission{book=" + bookName + ", slot=" + slotIndex + ", area=" + area + ", material=" + material + "}";
        }
    }

    // -------------------- Lifecycle --------------------
    @Override
    public void onEnable(MinecraftClient client) {
        dbg(client, "enabled (emissary mode)");
        state = State.SEEK_EMISSARY_ANCHOR;

        emissaryAnchor = null;
        lastEmissaryPos = null;
        emissaryEntity = null;

        active = null;
        lastChatSeen = 0;

        tryStopPathfind();
        tryStopMiner();
    }

    @Override
    public void onDisable() {
        MinecraftClient client = MinecraftClient.getInstance();
        dbg(client, "disabled");

        tryStopPathfind();
        tryStopMiner();
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!enabled) return;
        if (client == null || client.player == null || client.world == null) return;
        if (pathfind == null || mithrilMiner == null) return;

        pollChat(client);

        switch (state) {
            case SEEK_EMISSARY_ANCHOR -> tickSeekEmissaryAnchor(client);
            case PATH_TO_EMISSARY_ANCHOR -> { /* callback-driven */ }
            case OPEN_EMISSARY_GUI -> tickOpenEmissaryGui(client);
            case READ_BOOKS_AND_SELECT -> tickReadBooksAndSelect(client);
            case PATH_TO_AREA -> { /* callback-driven */ }
            case START_MINING -> tickStartMining(client);
            case WAIT_FOR_COMPLETE_CHAT -> { /* completion via chat poll */ }
            case RETURN_TO_EMISSARY -> tickReturnToEmissary(client);
            case CLAIM_BOOK -> tickClaimBook(client);
        }
    }

    // -------------------- Ticks --------------------
    private void tickSeekEmissaryAnchor(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (p == null) return;

        Vec3d pos = p.getPos();
        emissaryAnchor = EMISSARY_ANCHORS.stream()
                .min(Comparator.comparingDouble(a -> a.squaredDistanceTo(pos)))
                .orElse(null);

        if (emissaryAnchor == null) return;
        lastEmissaryPos = emissaryAnchor;

        dbg(client, "nearest emissary anchor: " + emissaryAnchor);

        startPathTo(client, emissaryAnchor, () -> {
            dbg(client, "arrived at emissary anchor");
            state = State.OPEN_EMISSARY_GUI;
        });
        state = State.PATH_TO_EMISSARY_ANCHOR;
    }

    private void tickOpenEmissaryGui(MinecraftClient client) {
        // If GUI already open, proceed
        if (client.currentScreen instanceof HandledScreen<?>) {
            state = State.READ_BOOKS_AND_SELECT;
            return;
        }

        // Find nearest emissary-like entity near the anchor, then interact
        emissaryEntity = findNearestEntityNear(client, lastEmissaryPos, 6.0);
        if (emissaryEntity == null) {
            // if no entity found, re-seek (maybe wrong anchor or not loaded)
            state = State.SEEK_EMISSARY_ANCHOR;
            return;
        }

        try {
            Objects.requireNonNull(client.interactionManager).interactEntity(client.player, emissaryEntity, Hand.MAIN_HAND);
        } catch (Throwable ignored) {}
    }

    private void tickReadBooksAndSelect(MinecraftClient client) {
        ScreenHandler handler = getScreenHandler(client.currentScreen);
        if (handler == null) return;

        List<EmissaryCommission> found = readCommissionsFromHandler(handler);
        if (found.isEmpty()) return;

        // Select by book order #1 then #2 then #3
        EmissaryCommission choice = chooseCommission(found);
        if (choice == null) return;

        active = choice;
        dbg(client, "selected: " + active);

        clickSlot(client, handler, active.slotIndex);

        // Close GUI
        try { client.player.closeHandledScreen(); } catch (Throwable ignored) {}

        AreaDef area = findArea(active.area);
        if (area == null) {
            dbg(client, "unknown area '" + active.area + "'; add it to AREAS");
            active = null;
            state = State.SEEK_EMISSARY_ANCHOR;
            return;
        }

        startPathTo(client, area.target, () -> {
            dbg(client, "arrived at area: " + area.areaName);
            state = State.START_MINING;
        });
        state = State.PATH_TO_AREA;
    }

    private void tickStartMining(MinecraftClient client) {
        try {
            mithrilMiner.setEnabled(true);
            mithrilMiner.onEnable(client);
        } catch (Throwable ignored) {}

        dbg(client, "mining started; waiting for completion chat");
        state = State.WAIT_FOR_COMPLETE_CHAT;
    }

    private void tickReturnToEmissary(MinecraftClient client) {
        if (lastEmissaryPos == null) {
            state = State.SEEK_EMISSARY_ANCHOR;
            return;
        }

        startPathTo(client, lastEmissaryPos, () -> {
            dbg(client, "arrived back at emissary");
            state = State.CLAIM_BOOK;
        });
        state = State.PATH_TO_EMISSARY_ANCHOR;
    }

    private void tickClaimBook(MinecraftClient client) {
        if (active == null) {
            state = State.SEEK_EMISSARY_ANCHOR;
            return;
        }

        // Ensure GUI open
        if (!(client.currentScreen instanceof HandledScreen<?>)) {
            state = State.OPEN_EMISSARY_GUI;
            return;
        }

        ScreenHandler handler = getScreenHandler(client.currentScreen);
        if (handler == null) return;

        // Re-read books to get current slot index (safer than old index)
        List<EmissaryCommission> found = readCommissionsFromHandler(handler);
        EmissaryCommission match = null;
        for (EmissaryCommission c : found) {
            if (c.bookName.equalsIgnoreCase(active.bookName)) { match = c; break; }
        }

        if (match != null) {
            dbg(client, "claiming by clicking: " + match.bookName);
            clickSlot(client, handler, match.slotIndex);
        } else {
            // Fallback: try original slot
            dbg(client, "claim match not found; clicking original slot " + active.slotIndex);
            clickSlot(client, handler, active.slotIndex);
        }

        try { client.player.closeHandledScreen(); } catch (Throwable ignored) {}

        // Reset and start next cycle
        active = null;
        state = State.SEEK_EMISSARY_ANCHOR;
    }

    // -------------------- Chat polling for completion --------------------
    private void pollChat(MinecraftClient client) {
        if (active == null) return;
        List<String> lines = readChatLines(client);
        if (lines.isEmpty()) return;

        if (lastChatSeen > lines.size()) lastChatSeen = 0;

        for (int i = lastChatSeen; i < lines.size(); i++) {
            String raw = strip(lines.get(i));
            if (raw == null || raw.isBlank()) continue;

            String upper = raw.toUpperCase(Locale.ROOT);

            // Require the "<AREA> <MATERIAL>" key AND "Commission Complete!"
            if (upper.contains(active.keyUpper())) {
                Matcher m = COMPLETE_LINE.matcher(raw);
                if (m.find()) {
                    dbg(client, "completion detected: " + raw);

                    tryStopMiner();

                    // Remember emissary location; go back and claim
                    state = State.RETURN_TO_EMISSARY;
                    break;
                }
            }
        }

        lastChatSeen = lines.size();
    }

    // -------------------- GUI parsing (NBT lore, no TooltipContext) --------------------
    private List<EmissaryCommission> readCommissionsFromHandler(ScreenHandler handler) {
        List<EmissaryCommission> out = new ArrayList<>();
        if (handler == null) return out;

        List<Slot> slots = handler.slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s == null) continue;

            ItemStack st = s.getStack();
            if (st == null || st.isEmpty()) continue;

            if (st.getItem() != Items.WRITABLE_BOOK) continue;

            String bookName = strip(st.getName().getString());
            if (!isCommissionBookName(bookName)) continue;

            LoreParse lp = parseLoreFromNbt(st);
            if (lp == null) continue;

            out.add(new EmissaryCommission(bookName, i, lp.area, lp.material));
        }

        return out;
    }

    private static final class LoreParse {
        final String area;
        final String material;
        LoreParse(String area, String material) { this.area = area; this.material = material; }
    }

    /**
     * Reads display.Lore from NBT. Expects a line that, once stripped, looks like:
     * "Royal Mines Titanium"
     *
     * area = everything except last token
     * material = last token
     */
    private LoreParse parseLoreFromNbt(ItemStack stack) {
        try {
            // Your mappings:
            // - ItemStack#getNbt() does not exist
            // - NbtCompound#getCompound(String) returns Optional<NbtCompound>
            // - NbtCompound#getList(String) returns Optional<NbtList>
            // - NbtList#getString(int) returns Optional<String>
            //
            // So we: (1) obtain the root tag via a compatibility helper, then (2) unwrap Optional values.
            NbtCompound tag = getRootTagCompat(stack);
            if (tag == null) return null;

            NbtCompound display = tag.getCompound("display").orElse(null);
            if (display == null) return null;

            if (!display.contains("Lore")) return null;

            NbtList lore = display.getList("Lore").orElse(null);
            if (lore == null) return null;

            for (int i = 0; i < lore.size(); i++) {
                String json = lore.getString(i).orElse("");
                if (json.isBlank()) continue;

                String line = strip(extractTextFromJsonComponent(json)).trim();
                if (line.isBlank()) continue;

                String low = line.toLowerCase(Locale.ROOT);
                if (low.contains("click")) continue;

                // Expect: "Royal Mines Titanium"
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String material = parts[parts.length - 1].trim();
                String area = line.substring(0, line.length() - material.length()).trim();

                if (!area.isBlank() && !material.isBlank()) {
                    return new LoreParse(area, material);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Best-effort root-tag getter for older/newer mappings.
     * Tries several common method names and finally scans for an NbtCompound field.
     */
    private static NbtCompound getRootTagCompat(ItemStack stack) {
        if (stack == null) return null;

        // Common accessor names across mappings/versions
        for (String mName : new String[]{"getTag", "getOrCreateTag", "getOrCreateNbt", "getNbt"}) {
            try {
                Method m = stack.getClass().getMethod(mName);
                Object o = m.invoke(stack);

                if (o instanceof NbtCompound c) return c;
                if (o instanceof Optional<?> opt && opt.orElse(null) instanceof NbtCompound c2) return c2;
            } catch (Throwable ignored) {
            }
        }

        // Declared methods (non-public)
        for (String mName : new String[]{"getTag", "getOrCreateTag", "getOrCreateNbt", "getNbt"}) {
            try {
                Method m = stack.getClass().getDeclaredMethod(mName);
                m.setAccessible(true);
                Object o = m.invoke(stack);

                if (o instanceof NbtCompound c) return c;
                if (o instanceof Optional<?> opt && opt.orElse(null) instanceof NbtCompound c2) return c2;
            } catch (Throwable ignored) {
            }
        }

        // Last resort: look for a field that is (or contains) an NbtCompound
        try {
            for (Field f : stack.getClass().getDeclaredFields()) {
                if (!NbtCompound.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object o = f.get(stack);
                if (o instanceof NbtCompound c) return c;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * Minimal JSON text component extractor.
     * Works for lore lines like {"text":"Royal Mines Titanium"} and similar.
     */
    private static String extractTextFromJsonComponent(String json) {
        if (json == null) return "";
        // Try Text.Serializer if present (varies by version); fallback to regex.
        try {
            // Text.Serializer.fromJson(String) exists in many versions, but not all.
            Class<?> serializer = Class.forName("net.minecraft.text.Text$Serializer");
            Method fromJson = serializer.getDeclaredMethod("fromJson", String.class);
            Object t = fromJson.invoke(null, json);
            if (t instanceof Text tt) return tt.getString();
        } catch (Throwable ignored) {}

        // Fallback: extract "text":"..."
        try {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"").matcher(json);
            if (m.find()) return unescapeJson(m.group(1));
        } catch (Throwable ignored) {}

        return json;
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r");
    }

    private static EmissaryCommission chooseCommission(List<EmissaryCommission> list) {
        EmissaryCommission c1 = null, c2 = null, c3 = null;
        for (EmissaryCommission c : list) {
            if (c.bookName.equalsIgnoreCase(BOOK1)) c1 = c;
            else if (c.bookName.equalsIgnoreCase(BOOK2)) c2 = c;
            else if (c.bookName.equalsIgnoreCase(BOOK3)) c3 = c;
        }
        if (c1 != null) return c1;
        if (c2 != null) return c2;
        return c3;
    }

    // -------------------- Screen/slot helpers --------------------
    private static ScreenHandler getScreenHandler(Screen screen) {
        if (!(screen instanceof HandledScreen<?> hs)) return null;
        try {
            return hs.getScreenHandler();
        } catch (Throwable t) {
            // reflection fallback
            try {
                Method m = hs.getClass().getMethod("getScreenHandler");
                Object o = m.invoke(hs);
                return (o instanceof ScreenHandler sh) ? sh : null;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void clickSlot(MinecraftClient client, ScreenHandler handler, int slotIdx) {
        try {
            if (client == null || client.player == null || client.interactionManager == null) return;
            client.interactionManager.clickSlot(handler.syncId, slotIdx, 0, SlotActionType.PICKUP, client.player);
        } catch (Throwable ignored) {}
    }

    // -------------------- Entity lookup (near anchor) --------------------
    private static Entity findNearestEntityNear(MinecraftClient client, Vec3d center, double radius) {
        if (client == null || client.world == null || client.player == null || center == null) return null;

        double r2 = radius * radius;
        Entity best = null;
        double bestD2 = Double.MAX_VALUE;

        // Broad scan: iterate world entities and pick the closest within radius.
        // (No name filter here to keep it reliable across NPC naming; proximity to anchor is the filter.)
        try {
            for (Entity e : client.world.getEntities()) {
                if (e == null || e.isRemoved()) continue;
                double d2 = e.getPos().squaredDistanceTo(center);
                if (d2 > r2) continue;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = e;
                }
            }
        } catch (Throwable ignored) {}

        return best;
    }

    // -------------------- Pathfinding wrappers --------------------
    private void startPathTo(MinecraftClient client, Vec3d target, Runnable onArrived) {
        if (pathfind == null) return;

        try {
            pathfind.setOnArrived(() -> {
                tryStopPathfind();
                if (onArrived != null) onArrived.run();
            });

            pathfind.setEnabled(true);
            pathfind.onEnable(client);
            pathfind.navigateTo(target);
        } catch (Throwable ignored) {}
    }

    private void tryStopPathfind() {
        if (pathfind == null) return;
        try { pathfind.setEnabled(false); } catch (Throwable ignored) {}
        try { pathfind.onDisable(); } catch (Throwable ignored) {}
    }

    private void tryStopMiner() {
        if (mithrilMiner == null) return;
        try { mithrilMiner.setEnabled(false); } catch (Throwable ignored) {}
        try { mithrilMiner.onDisable(); } catch (Throwable ignored) {}
    }

    // -------------------- Chat read (best-effort reflection) --------------------
    private static List<String> readChatLines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        if (client == null || client.inGameHud == null) return out;

        try {
            Object chatHud = client.inGameHud.getChatHud();

            // Try getMessages()
            try {
                Method m = chatHud.getClass().getMethod("getMessages");
                Object o = m.invoke(chatHud);
                if (o instanceof List<?> lst) {
                    for (Object entry : lst) {
                        String s = extractChatLineText(entry);
                        if (s != null) out.add(s);
                    }
                    return out;
                }
            } catch (Throwable ignored) {}

            // Try fields
            for (String fName : new String[]{"messages", "visibleMessages"}) {
                try {
                    Field f = chatHud.getClass().getDeclaredField(fName);
                    f.setAccessible(true);
                    Object o = f.get(chatHud);
                    if (o instanceof List<?> lst) {
                        for (Object entry : lst) {
                            String s = extractChatLineText(entry);
                            if (s != null) out.add(s);
                        }
                        return out;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        return out;
    }

    private static String extractChatLineText(Object entry) {
        if (entry == null) return null;

        try {
            for (String mName : new String[]{"getText", "getContent"}) {
                try {
                    Method m = entry.getClass().getMethod(mName);
                    Object o = m.invoke(entry);
                    if (o instanceof Text t) return t.getString();
                    if (o != null) return o.toString();
                } catch (Throwable ignored) {}
            }

            for (String fName : new String[]{"content", "text"}) {
                try {
                    Field f = entry.getClass().getDeclaredField(fName);
                    f.setAccessible(true);
                    Object o = f.get(entry);
                    if (o instanceof Text t) return t.getString();
                    if (o != null) return o.toString();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try { return entry.toString(); } catch (Throwable ignored) {}
        return null;
    }

    // -------------------- Utility --------------------
    private static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("\\u00A7[0-9A-FK-ORa-fk-or]", "");
    }
}
