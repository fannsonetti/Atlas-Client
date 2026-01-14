package name.atlasclient.ui;

import name.atlasclient.script.Script;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class ScriptDetailsScreen extends Screen {
    private final Screen parent;
    private final Script script;

    public ScriptDetailsScreen(Screen parent, Script script) {
        super(Text.literal("Script Details"));
        this.parent = parent;
        this.script = script;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Back"),
                btn -> MinecraftClient.getInstance().setScreen(parent)
        ).dimensions(this.width / 2 - 45, this.height - 28, 90, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(script.displayName()), this.width / 2, 18, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("ID: " + script.id()), this.width / 2, 34, 0x808080);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Enabled: " + script.isEnabled()), this.width / 2 - 120, 60, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal(script.description()), this.width / 2 - 120, 80, 0xC0C0C0);
        super.render(context, mouseX, mouseY, delta);
    }
}
