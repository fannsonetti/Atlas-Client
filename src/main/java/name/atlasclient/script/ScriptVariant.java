package name.atlasclient.script;

/**
 * Simple value object representing a script variant (id + display label).
 */
public final class ScriptVariant {
    private final String id;
    private final String label;

    public ScriptVariant(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
