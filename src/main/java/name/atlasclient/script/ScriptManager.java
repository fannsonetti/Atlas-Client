package name.atlasclient.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScriptManager {
    private static final List<Script> SCRIPTS = new ArrayList<>();

    public static void register(Script script) {
        SCRIPTS.add(script);
    }

    public static List<Script> all() {
        return Collections.unmodifiableList(SCRIPTS);
    }

    public static boolean anyRunning() {
        for (Script s : SCRIPTS) {
            if (s.isEnabled()) return true;
        }
        return false;
    }

    // No args. No Minecraft dependency. Just disable.
    public static void stopAll() {
        for (Script s : SCRIPTS) {
            if (s.isEnabled()) {
                s.setEnabled(false);
                s.onDisable();
            }
        }
    }

    private ScriptManager() {}
}
