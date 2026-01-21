package name.atlasclient.config.farming;

public final class WartCropsConfig {

    private static String direction = "left";   // "left" or "right"
    private static int lanes = 3;
    private static int runs = 1;
    private static double extraTime = 1.0;

    private static boolean rightClickHarvest = false;
    private static boolean asymmetricMove = false;

    private WartCropsConfig() {}

    public static String getDirection() { return direction; }
    public static void setDirection(String v) {
        direction = (v == null) ? "left" : v.toLowerCase();
    }

    public static int getLanes() { return lanes; }
    public static void setLanes(int v) { lanes = Math.max(1, v); }

    public static int getRuns() { return runs; }
    public static void setRuns(int v) { runs = Math.max(1, v); }

    public static double getExtraTime() { return extraTime; }
    public static void setExtraTime(double v) { extraTime = Math.max(0.0, v); }

    public static boolean isRightClickHarvest() { return rightClickHarvest; }
    public static void setRightClickHarvest(boolean v) { rightClickHarvest = v; }

    public static boolean isAsymmetricMove() { return asymmetricMove; }
    public static void setAsymmetricMove(boolean v) { asymmetricMove = v; }
}
