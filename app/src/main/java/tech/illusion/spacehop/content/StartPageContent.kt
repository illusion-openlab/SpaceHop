package tech.illusion.spacehop.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import tech.illusion.spacehop.R

/** design-contract.md §2 (v3 increment): gameplay_entry_button / gameplay_info_overlay. Sizing for
 * the info card and its entry badge — not covered by [PanelStyle], which only holds values shared
 * by every card (corner radius, the app's one accent). */
private val GAMEPLAY_CARD_WIDTH: Dp = 460.dp
private val GAMEPLAY_CARD_MAX_HEIGHT: Dp = 420.dp
private val GAMEPLAY_BADGE_SIZE: Dp = 18.dp

// Gate-A-confirmed final spec: same accent role as start_button's container, alpha dropped so the
// entry button reads as "same color family, but secondary" rather than competing with start_button
// for attention. Content (icon + text) stays near-opaque so it's still crisply legible.
private const val GAMEPLAY_ENTRY_CONTAINER_ALPHA = 0.3f
private const val GAMEPLAY_BADGE_FILL_ALPHA = 0.68f
// Scrim alpha is a placeholder per the design increment ("具体取值上真机定,模拟器固定相机给不出可信
// 数值") — Gate B must confirm this actually reads as "dimmed background" on-device, not just in
// theory.
private const val GAMEPLAY_SCRIM_ALPHA = 0.6f

/** design-contract.md §2: start_title / start_best_score / start_button, plus the premise line. */
@Composable
fun StartPageContent(bestScore: Int?, onStart: () -> Unit) {
    // Purely local to this composable, not a HopPhase branch — see gameplay_info_overlay in the
    // design contract: it's a bounded panel patch on start_page, not a new AttachmentPanel state.
    var showGameplayInfo by remember { mutableStateOf(false) }
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
        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // gameplay_entry_button used to be a sibling of this Column instead of living inside
            // it — both anchored in the same Box(contentAlignment = Center), so a TopEnd-aligned
            // button and a Center-aligned Column share the same coordinate space rather than
            // reserving room for each other. With a 5-character headlineLarge title
            // ("请不要吃我"), that put the button's badge directly over the title's last
            // character (caught on-device, not in code review). Giving it its own row as the
            // first child here means the title is pushed down instead of covered; the Column's
            // own Modifier.width(IntrinsicSize.Max) gives this fillMaxWidth row a real width to
            // resolve TopEnd against, since the outer Box has no fixed size of its own.
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showGameplayInfo = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PanelStyle.Accent.copy(alpha = GAMEPLAY_ENTRY_CONTAINER_ALPHA),
                        contentColor = PanelStyle.Accent,
                    ),
                    size = ButtonDefaults.Small,
                    leadingIcon = { GameplayInfoBadge() },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Text(text = "玩法", style = PicoTheme.typography.labelLarge)
                }
            }
            Text(
                // From the string resource, so the title and the launcher label can't drift apart.
                text = stringResource(R.string.app_name),
                style = PicoTheme.typography.headlineLarge,
                color = PicoTheme.colorScheme.labelPrimary,
                modifier = Modifier.padding(top = 6.dp),
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
                onClick = {
                    // Modal-exclusivity requirement: force the gameplay-info overlay closed before
                    // a run actually starts. Structurally this path shouldn't be reachable while
                    // the overlay is open — GameplayInfoOverlay's scrim consumes every pointer
                    // event over this whole card and start_button sits underneath it — but this
                    // stays as a cheap, explicit guarantee rather than relying solely on that.
                    showGameplayInfo = false
                    onStart()
                },
                // Single-accent palette (option A): the accent is the only non-neutral color in
                // the app, so the button uses it too rather than introducing a second color.
                colors = ButtonDefaults.buttonColors(containerColor = PanelStyle.Accent),
                modifier = Modifier.padding(top = 22.dp),
            ) {
                Text(text = "开始游戏", style = PicoTheme.typography.labelLarge)
            }
        }
        AnimatedVisibility(visible = showGameplayInfo, enter = fadeIn(), exit = fadeOut()) {
            GameplayInfoOverlay(onDismiss = { showGameplayInfo = false })
        }
    }
}

/** The small "?" badge that fills gameplay_entry_button's `leadingIcon` slot — a plain drawn
 * circle, not an `androidx.compose.material.icons` icon: this workspace has no icon drawable
 * resources, and that icon package is rejected outright as a Material-package violation by this
 * workspace's spatial-ui-design-style checks. */
@Composable
private fun GameplayInfoBadge() {
    Box(
        modifier = Modifier
            .size(GAMEPLAY_BADGE_SIZE)
            .clip(CircleShape)
            .background(PanelStyle.Accent.copy(alpha = GAMEPLAY_BADGE_FILL_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        // labelPrimaryLight = "the foreground color used on fillPrimary" per the SDK's own
        // ColorScheme doc — the one role this project's palette offers specifically for text
        // sitting on top of a solid color fill, rather than a role tuned for the glass panel
        // background everywhere else in this project.
        Text(text = "?", style = PicoTheme.typography.labelSmall, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

/** gameplay_info_overlay: a scrim (dismiss-on-tap, and — per project memory
 * spatialui-overlay-scrim-rules — must itself consume the tap so it can't fall through to
 * start_button underneath) plus a centered glass info card. Not a second AttachmentPanel; it's a
 * plain Compose overlay inside start_page's own Box, sized by the card itself
 * (`Modifier.matchParentSize()` on the scrim below is the standard Compose idiom for "scrim fills
 * whatever the dialog content ends up sizing this Box to"). */
@Composable
private fun GameplayInfoOverlay(onDismiss: () -> Unit) {
    Box {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(PicoTheme.colorScheme.fillTertiary.copy(alpha = GAMEPLAY_SCRIM_ALPHA))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(GAMEPLAY_CARD_WIDTH)
                .heightIn(max = GAMEPLAY_CARD_MAX_HEIGHT)
                .clip(RoundedCornerShape(PanelStyle.CardCornerRadius))
                .backgroundMaterial(true, Material.Regular)
                // Consumes its own taps so a tap on the card (not just its close button) can't
                // fall through to the scrim behind it and dismiss the overlay by accident.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(28.dp),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "玩法说明",
                        style = PicoTheme.typography.headlineLarge,
                        color = PicoTheme.colorScheme.labelPrimary,
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PicoTheme.colorScheme.fillSecondary,
                            contentColor = PicoTheme.colorScheme.labelPrimary,
                        ),
                        size = ButtonDefaults.Min,
                    ) {
                        Text(text = "×", style = PicoTheme.typography.titleSmall)
                    }
                }
                GameplaySection(
                    title = "简介",
                    bodyLines = listOf(GAMEPLAY_INTRO_TEXT),
                    modifier = Modifier.padding(top = 20.dp),
                )
                GameplaySection(
                    title = "操作",
                    bodyLines = listOf(GAMEPLAY_CONTROLS_TEXT),
                    modifier = Modifier.padding(top = 20.dp),
                )
                GameplaySection(
                    title = "规则",
                    bodyLines = GAMEPLAY_RULES_LINES,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}

/** One "简介"/"操作"/"规则" block: a small accent-colored heading (per the confirmed spec — the
 * only text in this card that isn't labelPrimary/labelSecondary) plus one [Text] per body line,
 * so the numbered "规则" list renders as five separate lines instead of one wrapped paragraph. */
@Composable
private fun GameplaySection(title: String, bodyLines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, style = PicoTheme.typography.titleSmall, color = PanelStyle.Accent)
        bodyLines.forEach { line ->
            Text(
                text = line,
                style = PicoTheme.typography.bodyMedium,
                color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

// Verbatim copy from the confirmed design increment — do not reword, trim, or summarize.
private const val GAMEPLAY_INTRO_TEXT =
    "你要操控一只从厨房水缸里逃出来的青蛙,靠捏合蓄力、松开起跳,一路跳过一个又一个盘子,尽量跳得又准又远,不断刷新自己的历史最高分。"

private const val GAMEPLAY_CONTROLS_TEXT =
    "- 蓄力起跳：拇指和食指靠拢开始蓄力,松开手指就会起跳,蓄的时间越久跳得越远,直到大约1.2秒封顶。" +
        "习惯用手柄的话,按住任意一侧扳机同样能蓄力,松开就起跳,效果跟捏合完全一样。"

private val GAMEPLAY_RULES_LINES = listOf(
    "1. 捏合(或按住扳机)开始蓄力,松手就起跳——起跳方向永远自动瞄准下一个盘子,你唯一要拿捏的是“蓄力多久”。",
    "2. 落地会有三种结果:卡得刚刚好是“完美”,差一点点也在容许范围内算“成功”,偏差太大就是“落空”。" +
        "落空会立刻结束这一局,直接弹出结算,不会问你要不要再试一次。",
    "3. 刚开始的厨房阶段,每一跳都有大约10秒的隐形倒计时。",
    "4. 连续跳上一阵子(或者跳得又快又稳),会触发一次特别的“穿窗跳跃”,带青蛙跳出厨房、跳进荷花池。",
    "5. 荷花池里没有倒计时压力,但盘子会越跳越远、容许的误差也越来越小,拼的完全是手感。",
)
