package name.atlasclient.config.sections.foraging;

import name.atlasclient.config.ConfigSection;
import name.atlasclient.config.foraging.HubForagerConfig;
import name.atlasclient.ui.AtlasMainScreen;

public final class HubForagerConfigSection implements ConfigSection {

    @Override public String id() { return "hub_forager"; }
    @Override public String tabId() { return "FORAGING"; }
    @Override public String title() { return "Hub Forager"; }
    @Override public int order() { return 100; }

    @Override
    public void buildWidgets(AtlasMainScreen screen, int cardX, int cardY, int cardW) {
        int pad = 10;
        int rowH = 56;

        int x = cardX + pad;
        int y = cardY + 34;
        int w = cardW - (pad * 2);

        screen.atlasAddDrawableChild(new AtlasMainScreen.AtlasToggleButton(
                x, y, w, rowH,
                HubForagerConfig::isEtherwarp,
                HubForagerConfig::setEtherwarp,
                "ON", "OFF",
                "Etherwarp",
                "Crouch while teleporting"
        ));
    }
}
