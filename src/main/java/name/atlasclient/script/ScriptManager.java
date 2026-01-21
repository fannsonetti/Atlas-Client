package name.atlasclient.script;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class ScriptManager {

    private static final List<Script> SCRIPTS = new ArrayList<>();
    private static boolean discovered = false;

    // We discover classes by walking this package on the mod filesystem.
    private static final String ROOT_PKG_PATH = "name/atlasclient/script/";

    private ScriptManager() {}

    /** Manual registration still supported; duplicates by id are ignored. */
    public static synchronized void register(Script script) {
        if (script == null) return;

        String id = safeLower(script.id());
        for (Script s : SCRIPTS) {
            if (safeLower(s.id()).equals(id)) return;
        }
        SCRIPTS.add(script);
    }

    /** Return all scripts; auto-discovery runs once. */
    public static synchronized List<Script> all() {
        ensureDiscovered();
        return Collections.unmodifiableList(SCRIPTS);
    }

    public static synchronized boolean anyRunning() {
        ensureDiscovered();
        for (Script s : SCRIPTS) {
            if (s.isEnabled()) return true;
        }
        return false;
    }

    public static synchronized void stopAll() {
        ensureDiscovered();
        for (Script s : SCRIPTS) {
            if (s.isEnabled()) {
                s.setEnabled(false);
                s.onDisable();
            }
        }
    }

    private static void ensureDiscovered() {
        if (discovered) return;
        discovered = true;

        ModContainer self = findContainerContainingClass("name/atlasclient/script/ScriptManager.class");
        if (self == null) return;

        // Find the script root folder within THIS mod container (works for jar + dev).
        Optional<Path> rootOpt = self.findPath(ROOT_PKG_PATH);
        if (rootOpt.isEmpty()) return;

        Path root = rootOpt.get();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().contains("$")) // ignore inner classes
                .forEach(p -> {
                    String className = toClassName(root, p);
                    tryRegisterClass(className);
                });
        } catch (Throwable ignored) {
            // Discovery failure should not crash the client
        }
    }

    private static ModContainer findContainerContainingClass(String resourcePath) {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            try {
                if (mod.findPath(resourcePath).isPresent()) {
                    return mod;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String toClassName(Path root, Path classFile) {
        // classFile is somewhere under .../name/atlasclient/script/...
        // We rebuild full name starting at name/atlasclient/script and convert to dotted form.
        String full = classFile.toString().replace('\\', '/');

        int idx = full.indexOf(ROOT_PKG_PATH);
        String rel = (idx >= 0) ? full.substring(idx) : classFile.getFileName().toString();

        if (rel.endsWith(".class")) {
            rel = rel.substring(0, rel.length() - ".class".length());
        }
        return rel.replace('/', '.');
    }

    private static void tryRegisterClass(String className) {
        try {
            Class<?> c = Class.forName(className);

            if (!Script.class.isAssignableFrom(c)) return;
            if (c.isInterface()) return;
            if (Modifier.isAbstract(c.getModifiers())) return;

            // Must have a public no-arg constructor
            Script script = (Script) c.getDeclaredConstructor().newInstance();
            register(script);

        } catch (Throwable ignored) {
            // Skip unloadable/uninstantiable classes
        }
    }

    private static String safeLower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }
}
