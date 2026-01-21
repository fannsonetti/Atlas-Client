package name.atlasclient.script;

import net.minecraft.util.Identifier;

public interface ScriptIconProvider {
    /**
     * 32x32 (recommended) GUI texture identifier.
     */
    Identifier iconTexture();
}
