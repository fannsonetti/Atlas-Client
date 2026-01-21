package name.atlasclient.config;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public final class ConfigManager {

    private static final List<ConfigSection> SECTIONS = new ArrayList<>();
    private static boolean discovered = false;

    private static final String ROOT_PKG_PATH = "name/atlasclient/config/sections/";
    private static final String SELF_CLASS_RESOURCE = "name/atlasclient/config/ConfigManager.class";

    private ConfigManager() {}

    /** Manual registration is supported; discovery is automatic. */
    public static synchronized void register(ConfigSection section) {
        if (section == null) return;

        for (ConfigSection s : SECTIONS) {
            if (s.id().equalsIgnoreCase(section.id())) return;
        }
        SECTIONS.add(section);
        SECTIONS.sort(Comparator.comparingInt(ConfigSection::order));
    }

    /** All discovered sections. */
    public static synchronized List<ConfigSection> getSections() {
        ensureDiscovered();
        return Collections.unmodifiableList(SECTIONS);
    }

    /** All sections belonging to a tab id (usually SettingsTab.name()). */
    public static synchronized List<ConfigSection> getSectionsForTab(String tabId) {
        ensureDiscovered();
        if (tabId == null) return List.of();

        List<ConfigSection> out = new ArrayList<>();
        for (ConfigSection s : SECTIONS) {
            if (tabId.equalsIgnoreCase(s.tabId())) out.add(s);
        }
        out.sort(Comparator.comparingInt(ConfigSection::order));
        return out;
    }

    private static void ensureDiscovered() {
        if (discovered) return;
        discovered = true;

        ModContainer self = findContainerContainingClass(SELF_CLASS_RESOURCE);
        if (self == null) return;

        Optional<Path> rootOpt = self.findPath(ROOT_PKG_PATH);
        if (rootOpt.isEmpty()) return;

        Path root = rootOpt.get();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.getFileName().toString().contains("$"))
                .forEach(p -> tryLoadSectionClass(toClassName(p)));
        } catch (Throwable ignored) {
        }
    }

    private static ModContainer findContainerContainingClass(String resourcePath) {
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            try {
                if (mod.findPath(resourcePath).isPresent()) return mod;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String toClassName(Path classFile) {
        String full = classFile.toString().replace('\\', '/');
        int idx = full.indexOf(ROOT_PKG_PATH);
        String rel = (idx >= 0) ? full.substring(idx) : classFile.getFileName().toString();

        if (rel.endsWith(".class")) rel = rel.substring(0, rel.length() - 6);
        return rel.replace('/', '.');
    }

    private static void tryLoadSectionClass(String className) {
        try {
            Class<?> c = Class.forName(className);

            // If a section uses static self-register, simply loading is enough.
            // If not, we also instantiate and register to be robust.
            if (ConfigSection.class.isAssignableFrom(c)
                    && !c.isInterface()
                    && !Modifier.isAbstract(c.getModifiers())) {
                register((ConfigSection) c.getDeclaredConstructor().newInstance());
            }
        } catch (Throwable ignored) {
        }
    }
}
