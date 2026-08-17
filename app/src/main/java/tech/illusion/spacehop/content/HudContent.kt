package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** design-contract.md §2/§3: hud_score always shown; hud_combo_badge only when streak >= 1.
 *
 * Single-accent palette (design review, option A): [PanelStyle.Accent] is the only non-neutral
 * color in the whole app, applied everywhere a score value shows up (here, the start page's best
 * score, and a new-record result title). The combo badge stays neutral so it doesn't compete
 * with the score for the same accent. */
@Composable
fun HudContent(score: Int, comboStreak: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // No fixed width — the panel wraps its content (the score, or score + combo badge once a
        // streak starts). A hardcoded 200dp left the score digit floating in a mostly-empty slab,
        // the same way the start page's fixed 480x320dp box did.
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .backgroundMaterial(true, Material.Regular)
            .padding(16.dp),
    ) {
        Text(
            text = score.toString(),
            style = PicoTheme.typography.displayLarge,
            color = PanelStyle.Accent,
        )
        if (comboStreak >= 1) {
            Text(
                text = "连击 x$comboStreak",
                style = PicoTheme.typography.labelLarge,
                color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .backgroundMaterial(false, Material.Regular)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
