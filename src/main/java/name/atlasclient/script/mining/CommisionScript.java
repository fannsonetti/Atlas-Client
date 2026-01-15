package name.atlasclient.script.mining;

import name.atlasclient.script.Script;
import name.atlasclient.script.misc.PathfindScript;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommisionScript implements Script {

    @Override public String id() { return "commision"; }
    @Override public String displayName() { return "Commision"; }
    @Override public String description() { return "Scans commissions and chains pathfinding + mithril mining."; }
    @Override public String category() { return "Mining"; }

    private boolean enabled = false;
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // ------------------------------------------------------------
    // Dependencies (late-bound to keep no-arg construction)
    // ------------------------------------------------------------
    private PathfindScript pathfind;
    private MithrilMiningScript mithrilMiner;

    /** No-arg constructor to match your ScriptManager.register(new X()). */
    public CommisionScript() {}

    /** Optional explicit wiring (recommended). */
    public void setDependencies(PathfindScript pathfind, MithrilMiningScript mithrilMiner) {
        this.pathfind = pathfind;
        this.mithrilMiner = mithrilMiner;
    }

    // ------------------------------------------------------------
    // CONFIG
    // ------------------------------------------------------------
    private static final String COMMISSION_TRIGGER = "Upper Mines Mithril";

    // Set this to your real arrival position
    private Vec3d upperMinesTarget = new Vec3d(0.5, 64.0, 0.5);

    public void setUpperMinesTarget(Vec3d pos) {
        if (pos != null) this.upperMinesTarget = pos;
    }

    // ------------------------------------------------------------
    // State
    // ------------------------------------------------------------
    private enum State { IDLE, PATHING, MINING }
    private State state = State.IDLE;

    private boolean commissionWasPresent = false;
    private final AtomicBoolean arrivalArmed = new AtomicBoolean(false);

    @Override
    public void onEnable(MinecraftClient client) {
        enabled = true;
        state = State.IDLE;
        commissionWasPresent = false;
        arrivalArmed.set(false);

        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("CommisionScript enabled."), false);
        }
    }

    @Override
    public void onDisable() {
        enabled = false;
        arrivalArmed.set(false);
        state = State.IDLE;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("CommisionScript disabled."), false);
        }
    }

    @Override
    public void onTick(MinecraftClient client) {
        if (!enabled) return;
        if (client == null || client.player == null || client.world == null) return;

        // Ensure dependencies are wired
        if (!ensureDependencies()) {
            // Avoid spamming chat every tick; only warn when enabled and missing.
            if (client.player.age % 60 == 0) {
                client.player.sendMessage(
                        Text.literal("[CommisionScript] Missing dependencies (PathfindScript/MithrilMiningScript)."),
                        false
                );
            }
            return;
        }

        List<String> sidebar = readSidebarLinesCompat(client);
        boolean present = containsLine(sidebar, COMMISSION_TRIGGER);

        boolean risingEdge = present && !commissionWasPresent;
        commissionWasPresent = present;

        if (risingEdge && state == State.IDLE) {
            startUpperMinesRun(client);
        }
    }

    private void startUpperMinesRun(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        mithrilMiner.setEnabled(false);
        arrivalArmed.set(true);

        pathfind.setOnArrived(() -> {
            if (!arrivalArmed.getAndSet(false)) return;

            pathfind.setEnabled(false);
            mithrilMiner.setEnabled(true);
            state = State.MINING;

            MinecraftClient c = MinecraftClient.getInstance();
            if (c != null && c.player != null) {
                c.player.sendMessage(Text.literal("Arrived at Upper Mines. Mithril miner enabled."), false);
            }
        });

        pathfind.setEnabled(true);
        pathfind.navigateTo(upperMinesTarget);
        state = State.PATHING;

        player.sendMessage(Text.literal("Commission detected. Pathfinding to Upper Mines..."), false);
    }

    // ------------------------------------------------------------
    // Dependency resolution
    // ------------------------------------------------------------

    /**
     * Recommended: call setDependencies(...) from your mod init.
     * This is a best-effort fallback that tries to find ScriptManager and pull script instances.
     * If your ScriptManager API differs, tell me what it is and I will tailor this to it.
     */
    private boolean ensureDependencies() {
        if (pathfind != null && mithrilMiner != null) return true;

        // Best-effort reflective lookup of ScriptManager.getById(...) or similar.
        try {
            Class<?> sm = Class.forName("name.atlasclient.script.ScriptManager");

            // Try common patterns in order
            Object path = tryGetScript(sm, "pathfind_script", PathfindScript.class);
            Object mith = tryGetScript(sm, "mithril_miner", MithrilMiningScript.class);

            if (pathfind == null && path instanceof PathfindScript) pathfind = (PathfindScript) path;
            if (mithrilMiner == null && mith instanceof MithrilMiningScript) mithrilMiner = (MithrilMiningScript) mith;

        } catch (Throwable ignored) {
            // ignore, we'll just report missing deps in onTick
        }

        return pathfind != null && mithrilMiner != null;
    }

    private static Object tryGetScript(Class<?> sm, String id, Class<?> expected) {
        // getById(String)
        try {
            Method m = sm.getDeclaredMethod("getById", String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, id);
            if (expected.isInstance(r)) return r;
        } catch (Throwable ignored) {}

        // get(String)
        try {
            Method m = sm.getDeclaredMethod("get", String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, id);
            if (expected.isInstance(r)) return r;
        } catch (Throwable ignored) {}

        // getScript(String)
        try {
            Method m = sm.getDeclaredMethod("getScript", String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, id);
            if (expected.isInstance(r)) return r;
        } catch (Throwable ignored) {}

        // getScripts() -> Collection
        try {
            Method m = sm.getDeclaredMethod("getScripts");
            m.setAccessible(true);
            Object r = m.invoke(null);
            if (r instanceof Iterable<?> it) {
                for (Object s : it) {
                    try {
                        Method mid = s.getClass().getMethod("id");
                        Object sid = mid.invoke(s);
                        if (sid instanceof String ss && ss.equalsIgnoreCase(id) && expected.isInstance(s)) {
                            return s;
                        }
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    // ------------------------------------------------------------
    // Sidebar parsing (compat via reflection)
    // ------------------------------------------------------------

    private static boolean containsLine(List<String> lines, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        String n = needle.toLowerCase(Locale.ROOT);
        for (String s : lines) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(n)) return true;
        }
        return false;
    }

    /**
     * Reads sidebar lines without hard-depending on mapping-specific classes like ScoreboardPlayerScore.
     */
    private static List<String> readSidebarLinesCompat(MinecraftClient client) {
        try {
            Scoreboard sb = client.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj == null) return Collections.emptyList();

            // Try: Scoreboard#getAllPlayerScores(ScoreboardObjective) (older yarn)
            Collection<?> scores = null;
            try {
                Method m = sb.getClass().getMethod("getAllPlayerScores", ScoreboardObjective.class);
                Object r = m.invoke(sb, obj);
                if (r instanceof Collection<?>) scores = (Collection<?>) r;
            } catch (Throwable ignored) {}

            // Try: Scoreboard#getScoreboardEntries(ScoreboardObjective) or getAllScores (varies)
            if (scores == null) {
                for (String name : new String[]{"getScoreboardEntries", "getAllScores", "getScores"}) {
                    try {
                        Method m = sb.getClass().getMethod(name, ScoreboardObjective.class);
                        Object r = m.invoke(sb, obj);
                        if (r instanceof Collection<?>) { scores = (Collection<?>) r; break; }
                    } catch (Throwable ignored) {}
                }
            }

            if (scores == null) return Collections.emptyList();

            // Convert score objects -> line strings
            List<Object> list = new ArrayList<>(scores);

            // Sort like sidebar: score desc then name asc (best-effort)
            list.sort((a, b) -> {
                int sa = reflectInt(a, "getScore", "getValue", "score");
                int sbv = reflectInt(b, "getScore", "getValue", "score");
                if (sa != sbv) return Integer.compare(sbv, sa);

                String na = reflectString(a, "getPlayerName", "getOwner", "getName", "playerName");
                String nb = reflectString(b, "getPlayerName", "getOwner", "getName", "playerName");
                return String.CASE_INSENSITIVE_ORDER.compare(na, nb);
            });

            List<String> out = new ArrayList<>(32);
            for (Object s : list) {
                // Try to get already-rendered text: getText()/getDisplayText() (varies)
                String line = reflectString(s, "getText", "getDisplayText", "getFormattedText");
                if (line == null || line.isEmpty()) {
                    // Fallback: just use playerName; Hypixel-like servers often put line content in that field anyway
                    line = reflectString(s, "getPlayerName", "getOwner", "getName", "playerName");
                }
                if (line != null) out.add(line);
                if (out.size() >= 32) break;
            }

            return out;

        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static int reflectInt(Object obj, String... methodsOrFields) {
        for (String m : methodsOrFields) {
            try {
                Method mm = obj.getClass().getMethod(m);
                Object r = mm.invoke(obj);
                if (r instanceof Integer i) return i;
            } catch (Throwable ignored) {}
            try {
                Field f = obj.getClass().getDeclaredField(m);
                f.setAccessible(true);
                Object r = f.get(obj);
                if (r instanceof Integer i) return i;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private static String reflectString(Object obj, String... methodsOrFields) {
        for (String m : methodsOrFields) {
            try {
                Method mm = obj.getClass().getMethod(m);
                Object r = mm.invoke(obj);
                if (r != null) return r.toString();
            } catch (Throwable ignored) {}
            try {
                Field f = obj.getClass().getDeclaredField(m);
                f.setAccessible(true);
                Object r = f.get(obj);
                if (r != null) return r.toString();
            } catch (Throwable ignored) {}
        }
        return "";
    }
}
