package tech.illusion.spacehop.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import tech.illusion.spacehop.R
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material

@Composable
fun HomeStage() {
    SpatialView(
        initial = { content, attachments ->
            val model = Entity.loadSuspend(uriString = "asset://box.usdz").apply { content
                components[TransformComponent::class.java]?.apply {
                    setEulerAngles(EulerAngles(90f, 0f, 0f))
                    setPosition(Vector3(0f, 1.5f, -1f))
                }
            }
            content.addEntity(model)

            val textAttachment = attachments.entity(id = "text")
            textAttachment?.apply {
                components[TransformComponent::class.java]?.apply {
                    setPosition(Vector3(0f, 1.8f, -1.2f))
                }
                content.addEntity(this)
            }
        },
        attachments = {
            AttachmentPanel(id = "text") {
                Box(
                    modifier =
                    Modifier
                        .size(640.dp, 256.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .backgroundMaterial(true, Material.Regular),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.hello),
                        color = PicoTheme.colorScheme.labelPrimary,
                        style = PicoTheme.typography.titleLarge.copy(
                            fontSize = 64.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    )
}
