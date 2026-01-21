package name.atlasclient.script;

import java.util.List;

/**
 * Scripts that support multiple run modes should implement this interface.
 * The UI can render these as "type buttons" on the script card.
 */
public interface VariantScript {

    /** List of supported variants. */
    List<ScriptVariant> variants();

    /** Current selected variant id (must match one returned by variants()). */
    String selectedVariantId();

    /** Select the active variant by id. */
    void setSelectedVariantId(String variantId);
}
