package name.atlasclient.config;

import name.atlasclient.ui.AtlasMainScreen;

public interface ConfigSection {

    /** Unique id (dedupe key). Example: "hub_forager" */
    String id();

    /** Settings tab this belongs to (typically SettingsTab.name()). Example: "FORAGING" */
    String tabId();

    /** Card title shown in the UI. Example: "Hub Forager" */
    String title();

    /** Sort order within its tab (low first). */
    int order();

    /**
     * Add widgets for this section.
     * cardX/cardY/cardW refer to the card placement within the right panel.
     */
    void buildWidgets(AtlasMainScreen screen, int cardX, int cardY, int cardW);
}
