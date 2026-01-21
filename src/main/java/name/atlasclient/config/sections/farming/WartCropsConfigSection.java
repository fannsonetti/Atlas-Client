package name.atlasclient.config.sections.farming;

import name.atlasclient.config.ConfigSection;
import name.atlasclient.config.farming.WartCropsConfig;
import name.atlasclient.ui.AtlasMainScreen;

public final class WartCropsConfigSection implements ConfigSection {

    @Override public String id() { return "wart_crops"; }

    // IMPORTANT: adjust this to match your existing tab IDs.
    // Your example uses "FORAGING". If you have a "FARMING" tab, use that.
    @Override public String tabId() { return "FARMING"; }

    @Override public String title() { return "Wart / Crops"; }
    @Override public int order() { return 110; }

    @Override
    public void buildWidgets(AtlasMainScreen screen, int cardX, int cardY, int cardW) {
        int pad = 10;
        int rowH = 20;

        int x = cardX + pad;
        int y = cardY + 34;
        int w = cardW - (pad * 2);

        // Toggle: right click harvest
        screen.atlasAddDrawableChild(new AtlasMainScreen.AtlasToggleButton(
                x, y, w, rowH,
                WartCropsConfig::isRightClickHarvest,
                WartCropsConfig::setRightClickHarvest,
                "ON", "OFF",
                "Right-click harvest",
                "Use right-click instead of left-click"
        ));
        y += rowH + 6;

        // Toggle: asymmetric movement
        screen.atlasAddDrawableChild(new AtlasMainScreen.AtlasToggleButton(
                x, y, w, rowH,
                WartCropsConfig::isAsymmetricMove,
                WartCropsConfig::setAsymmetricMove,
                "ON", "OFF",
                "Asymmetric move",
                "Right direction uses D only (no W)"
        ));
        y += rowH + 6;

        // Direction / lanes / runs / extraTime:
        // Add these if your UI has dropdowns/sliders/text fields.
        // If you tell me what widgets exist (or point me to another ConfigSection example),
        // I can wire them precisely.
    }
}
