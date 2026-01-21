package name.atlasclient.config;

public final class Debug {

    private static boolean debugMessages = false;

    private Debug() {
        // Prevent instantiation
    }

    public static boolean isDebugMessages() {
        return debugMessages;
    }

    public static void setDebugMessages(boolean value) {
        debugMessages = value;
    }

    public static void toggleDebugMessages() {
        debugMessages = !debugMessages;
    }
}
