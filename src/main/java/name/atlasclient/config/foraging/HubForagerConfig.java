package name.atlasclient.config.foraging;

public final class HubForagerConfig {

    private static boolean etherwarp = true;

    private HubForagerConfig() {}

    /** If true: crouch while teleport-right-clicking. */
    public static boolean isEtherwarp() {
        return etherwarp;
    }

    public static void setEtherwarp(boolean v) {
        etherwarp = v;
    }
}
