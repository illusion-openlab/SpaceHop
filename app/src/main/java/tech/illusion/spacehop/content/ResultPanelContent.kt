package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** design-contract.md §2: result_title / result_score / result_best_combo / result_best_score
 * / result_divider / retry_button. */
@Composable
fun ResultPanelContent(
    score: Int,
    bestCombo: Int,
    bestScore: Int,
    isNewRecord: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(PanelStyle.CardCornerRadius))
            .backgroundMaterial(true, Material.Regular)
            .padding(28.dp),
    ) {
        Text(
            text = if (isNewRecord) "新纪录！" else "本局结束",
            style = PicoTheme.typography.headlineLarge,
            // Celebratory color only on an actual new record — a plain run stays neutral so the
            // accent keeps meaning instead of showing on every result.
            color = if (isNewRecord) PanelStyle.Accent else PicoTheme.colorScheme.labelPrimary,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        // Single-accent palette (option A): the accent marks this run's score, matching the HUD
        // readout and the start page's best-score chip. Combo and all-time best stay neutral —
        // neither is "the score," and giving every stat its own color read as cluttered.
        ResultRow("本局分数", score.toString(), PanelStyle.Accent)
        ResultRow("最高连击", "x$bestCombo", PicoTheme.colorScheme.labelPrimary)
        ResultRow("历史最高", bestScore.toString(), PicoTheme.colorScheme.labelPrimary)
        HorizontalDivider(color = PicoTheme.colorScheme.dividerLine)
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = PanelStyle.Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(text = "再来一局", style = PicoTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = PicoTheme.typography.bodyLarge,
            color = PicoTheme.colorScheme.labelSecondary,
            modifier = Modifier.fillMaxWidth(0.5f),
        )
        Text(
            text = value,
            style = PicoTheme.typography.titleMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
