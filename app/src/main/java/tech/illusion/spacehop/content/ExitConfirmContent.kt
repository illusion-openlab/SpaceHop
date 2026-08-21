package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** Back-press exit confirmation for an in-progress run. Shown over the Playing phase's
 * otherwise-empty primary panel (see `HomeStage`'s `AttachmentPanel(PRIMARY_PANEL)`) while
 * HomeStage's own `paused` flag holds its round-timeout watchdog and charge-visual ticks still —
 * DefaultStage has no window chrome or close button, and PICO OS remaps several distinct
 * physical controller inputs straight into a plain `KEYCODE_BACK` `KeyEvent` (confirmed on real
 * hardware), so an un-intercepted back during play would otherwise silently call
 * `Activity.finish()` mid-round with zero warning. Root-caused from an App Store review report
 * ("遇到崩溃") that traced to exactly this — no real crash involved. */
@Composable
fun ExitConfirmContent(onCancel: () -> Unit, onExit: () -> Unit) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(PanelStyle.CardCornerRadius))
            .backgroundMaterial(true, Material.Regular)
            .padding(28.dp),
    ) {
        Text(
            text = "退出游戏？",
            style = PicoTheme.typography.headlineLarge,
            color = PicoTheme.colorScheme.labelPrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "当前进度将不会保存",
            style = PicoTheme.typography.bodyMedium,
            color = PicoTheme.colorScheme.labelSecondary,
        )
        // Cancel first and accented: the safer, recommended action for an exit-confirm dialog
        // shouldn't be the de-emphasized one — mirrors the single-accent convention every other
        // panel in this project already uses (PanelStyle.Accent), rather than inventing a new
        // destructive/red color role this project has none of.
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = PanelStyle.Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text(text = "取消", style = PicoTheme.typography.labelLarge)
        }
        Button(
            onClick = onExit,
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(text = "退出", style = PicoTheme.typography.labelLarge)
        }
    }
}
