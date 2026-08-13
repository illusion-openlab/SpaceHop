package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

/** design-contract.md §2: start_title / start_best_score / start_button. */
@Composable
fun StartPageContent(bestScore: Int?, onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 480.dp, height = 320.dp)
            .clip(RoundedCornerShape(26.dp))
            .backgroundMaterial(true, Material.Regular)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "SpaceHop",
                style = PicoTheme.typography.headlineSmall,
                color = PicoTheme.colorScheme.labelPrimary,
            )
            Text(
                text = "历史最高 ${bestScore ?: 0}",
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Button(onClick = onStart, modifier = Modifier.padding(top = 22.dp)) {
                Text(text = "开始游戏", style = PicoTheme.typography.labelLarge)
            }
        }
    }
}
