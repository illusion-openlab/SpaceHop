package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spacehop.R

/** design-contract.md §2: start_title / start_best_score / start_button, plus the premise line. */
@Composable
fun StartPageContent(bestScore: Int?, onStart: () -> Unit) {
    Box(
        // No explicit size — per SDK docs, an AttachmentPanel (and this Box within it) adapts to
        // its content's size by default; a fixed 480x320dp box left visible padding around the
        // handful of short text lines below, reading as an oversized/too-wide panel.
        modifier = Modifier
            .clip(RoundedCornerShape(PanelStyle.CardCornerRadius))
            .backgroundMaterial(true, Material.Regular)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                // From the string resource, so the title and the launcher label can't drift apart.
                text = stringResource(R.string.app_name),
                style = PicoTheme.typography.headlineLarge,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Text(
                text = stringResource(R.string.start_premise),
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            // A small readout chip rather than plain caption text — the best score is a stat to
            // be read at a glance, like the in-run HUD, not a footnote.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .backgroundMaterial(false, Material.Regular)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "历史最高",
                    style = PicoTheme.typography.labelMedium,
                    color = PicoTheme.colorScheme.labelSecondary,
                )
                Text(
                    text = "${bestScore ?: 0}",
                    style = PicoTheme.typography.titleMedium,
                    color = PanelStyle.Accent,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Button(
                onClick = onStart,
                // Single-accent palette (option A): the accent is the only non-neutral color in
                // the app, so the button uses it too rather than introducing a second color.
                colors = ButtonDefaults.buttonColors(containerColor = PanelStyle.Accent),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                Text(text = "开始游戏", style = PicoTheme.typography.labelLarge)
            }
        }
    }
}
