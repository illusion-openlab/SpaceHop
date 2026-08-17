package tech.illusion.spacehop.content

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.vibrant.Vibrant
import com.pico.spatial.ui.foundation.vibrant.withVibrant

/** Shared corner radius for the primary cards (start/result) so the two read as one family —
 * previously 26dp and 20dp respectively, a difference with no intended meaning. */
object PanelStyle {
    val CardCornerRadius = 24.dp

    /** The single accent color across every panel (score readouts, buttons, the new-record
     * title). A specific military-green brand color, not one of `PicoTheme.colorScheme`'s
     * semantic roles — those roles are hue-adaptive by design, so there's no role that means
     * "always this exact green." `Vibrant.None` pins the literal hex so the surrounding glass
     * material's Vibrant context can't reinterpret it (see the SDK's Vibrant-system docs: this
     * is the same mechanism used for fixed-color icons/badges). The trade-off: this color no
     * longer auto-adjusts for contrast against the passthrough background the way an adaptive
     * role would. */
    val Accent = Color(0xFF4B5320).withVibrant(Vibrant.None) // design-style: fixed-figma-color user-specified military-green brand accent, not from Figma
}
