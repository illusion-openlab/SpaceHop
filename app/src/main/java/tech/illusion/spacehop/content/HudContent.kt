package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** design-contract.md §2/§3: hud_score always shown; hud_combo_badge only when streak >= 1. */
@Composable
fun HudContent(score: Int, comboStreak: Int) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .clip(RoundedCornerShape(18.dp))
            .backgroundMaterial(true, Material.Regular)
            .padding(16.dp),
    ) {
        Text(
            text = score.toString(),
            style = PicoTheme.typography.displayMedium,
            color = PicoTheme.colorScheme.labelPrimary,
        )
        if (comboStreak >= 1) {
            Text(
                text = "连击 x$comboStreak",
                style = PicoTheme.typography.labelLarge,
                color = PicoTheme.colorScheme.labelPrimary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .backgroundMaterial(false, Material.Regular)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
