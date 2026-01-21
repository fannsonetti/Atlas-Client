package name.atlasclient.ui;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class HudPanel {
    public Text titleLeft;
    public Text titleRight;
    public Text subtitle;

    public final List<Section> sections = new ArrayList<>();

    public static final class Section {
        public final Text header;
        public final List<Text> lines;

        public Section(Text header, List<Text> lines) {
            this.header = header;
            this.lines = lines;
        }
    }
}
