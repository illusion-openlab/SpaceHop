# SpaceHop 设计契约

## 0. 元信息

- 应用名 / applicationId：SpaceHop / tech.illusion.spacehop
- 设计源类型：文字 PRD
- 设计源位置：docs/superpowers/specs/2026-08-13-space-hop-design.md；视觉稿 design-ref/design-mockup.html
- 契约版本：v3
- 用户确认时间：2026-08-13（v1）；2026-08-26（v2 增量：开始页"玩法"入口按钮 + 玩法说明浮层，门禁 A 已确认）；2026-09-21（v3 增量：窗口容器（WindowContainer）UI 与退出沉浸可达性修复——`feat/window-mode` 分支已实现并经门禁 B 真机验证（见 `.superpowers/sdd/2026-09-21-spacehop-window-mode/task-10-report.md`），本增量是最终整分支评审后的回顾性记录，不是新的门禁 A 设计稿）

## 1. 面板清单

| 面板 id | 容器类型 | 面板尺寸 | 层级/父面板 | 出现时机 |
|---|---|---|---|---|
| start_page | WindowContainer | 480×320dp | 舞台前方锚点 | 冷启动，游戏尚未开始过 |
| hud | WindowContainer | 200×180dp | 舞台侧方锚点 | 游玩中（Playing 态） |
| result_panel | WindowContainer | 320×360dp | 舞台前方锚点 | 落空后（Result 态） |
| gameplay_info_overlay | Box 叠加层（不是独立 WindowContainer/AttachmentPanel，与 start_page 共用同一个 primary AttachmentPanel，由 `StartPageContent` 内部 `remember { mutableStateOf(false) }` 控制显隐） | 380dp × wrap（maxHeight 420dp，内部竖向可滚动） | 叠加于 start_page 之上（同一 Compose 子树内的兄弟层，不是新窗口） | 点击 gameplay_entry_button 后（v2 新增） |
| main_toolbar | WindowContainer（`Augment`，`NormalizedPoint3D.BottomFront` 锚点 + `AugmentContentAlignment.BottomCenter`，`enableMaterialBackground = false`——面板自己带毛玻璃，Augment 再叠一层会变成两个共面半透明层） | wrap×wrap（`Column`，随当前 phase 的内容自适应） | 窗口态主视图（`SpatialView`）下方，独立于视图本体之外 | 窗口态任意 phase 常驻（v3 新增，见下方状态清单的 enter_immersive_button 行） |

> 备注（v2）：start_page 面板本身是"内容自适应尺寸"（详见 StartPageContent.kt 顶部注释），与本表 v1 记录的 480×320dp 定值不一致——这是已有的文档/代码漂移，与本次增量无关。浮层展开后，最外层 Box 会因新增内容变宽/变高而让整个 primary AttachmentPanel 跟着变大，这是预期行为。

> 备注（v3）：gameplay_info_overlay 的宽度从 460dp 收窄到 380dp——最终整分支评审 I3：这张卡是 start_page 自身外层 `backgroundMaterial` Box（无固定尺寸，随最宽子节点撑开）的子节点，那层 Box 还会再加上自己的 32dp×2 padding；460dp 时把外层 Box 撑到 460+64=524dp，超过本工作区实测的 ~476dp 阈值——`backgroundMaterial` 玻璃卡片一旦超过这个宽度就会整卡子节点全部不渲染、不报错不打日志。由于 start_title/start_best_score/start_button 和浮层共用同一个外层 Box，超阈值会把整个起始页拖空白，不只是浮层本身。380dp 把外层 Box 压到 444dp，留了约 32dp 余量。详见 `StartPageContent.kt` 的 `GAMEPLAY_CARD_WIDTH` 注释。
>
> 备注（v3）：hud 面板在沉浸态（Stage 的 `side` AttachmentPanel）不再只在 Playing 态出现——最终整分支评审 I1：退出沉浸按钮（元素表 exit_immersive_button）现在 NotStarted/Playing/Result 三态全程常驻，玩家在还没开局或已经结算时也能退出沉浸；只有比分/连击数字仍然只在 Playing 态显示（`HudContent` 内部按 phase 分支，不是外层面板级的显隐）。窗口态的 hud 内容本身没有变化，依旧只出现在 `WindowHome.kt` 的 `when(phase) -> Playing` 分支里。

## 2. 元素表

| id | 类型 | 父容器 | 锚点 | 偏移 | 尺寸 | 颜色角色 | 字体角色 |
|---|---|---|---|---|---|---|---|
| start_title | Text | start_page | 上中 | 0,0 | wrap×wrap | labelPrimary | headlineSmall |
| start_best_score | Text | start_page | 上中 | 0,44 | wrap×wrap | labelSecondary | bodyMedium |
| start_button | Button | start_page | 下中 | 0,0 | wrap×wrap | labelPrimary on fillPrimary | labelLarge |
| hud_score | Text | hud | 左上 | 0,0 | wrap×wrap | labelPrimary | displayMedium |
| hud_combo_badge | Text | hud | 左上 | 0,64 | wrap×wrap | labelPrimary on fillTertiary | labelLarge |
| result_title | Text | result_panel | 上中 | 0,0 | wrap×wrap | labelPrimary | headlineLarge |
| result_score | Text | result_panel | 上中 | 0,50 | wrap×wrap | labelPrimary | displayMedium |
| result_best_combo | Text | result_panel | 上中 | 0,110 | wrap×wrap | labelSecondary | bodyLarge |
| result_best_score | Text | result_panel | 上中 | 0,144 | wrap×wrap | labelSecondary | bodyLarge |
| result_divider | Divider | result_panel | 上中 | 0,178 | fill×1dp | dividerLine | - |
| retry_button | Button | result_panel | 下中 | 0,0 | wrap×wrap | labelPrimary on fillPrimary | labelLarge |
| gameplay_entry_button | Button（size=Small，leadingIcon=?徽标） | start_page | 右上（Box 右上角，Column 的兄弟节点） | 0,0 | wrap×wrap | 容器：PanelStyle.Accent（alpha 0.3）；内容：PanelStyle.Accent（近满不透明） | labelLarge |
| gameplay_info_scrim | Box（Scrim 遮罩，pointerInput 消费点击） | gameplay_info_overlay | 撑满（matchParentSize） | 0,0 | fill×fill | colorScheme.fillTertiary（alpha 0.6，模拟器相机不可信，最终取值上真机定） | - |
| gameplay_info_card | Box（玻璃卡片容器，backgroundMaterial(true, Material.Regular) + clip(PanelStyle.CardCornerRadius)，pointerInput 消费点击防穿透） | gameplay_info_overlay | 居中 | 0,0 | 380dp × wrap（maxHeight 420dp，内部 Column + verticalScroll；v3 从 460dp 收窄，见上方备注） | 见子元素 | - |
| gameplay_info_title | Text | gameplay_info_card | 上左（标题行，与关闭按钮同一行，SpaceBetween） | 0,0 | wrap×wrap | labelPrimary | headlineLarge |
| gameplay_info_close_button | Button（size=Min） | gameplay_info_card | 上右（与标题同一行） | 0,0 | wrap×wrap | labelPrimary on fillSecondary | titleSmall |
| gameplay_info_section_intro_title | Text | gameplay_info_card | 左对齐 | 0,继标题栏之后 | wrap×wrap | PanelStyle.Accent | titleSmall |
| gameplay_info_section_intro_body | Text | gameplay_info_card | 左对齐 | 0,继上一行 | wrap×wrap | labelSecondary | bodyMedium |
| gameplay_info_section_controls_title | Text | gameplay_info_card | 左对齐 | 0,继上一行 | wrap×wrap | PanelStyle.Accent | titleSmall |
| gameplay_info_section_controls_body | Text | gameplay_info_card | 左对齐 | 0,继上一行 | wrap×wrap | labelSecondary | bodyMedium |
| gameplay_info_section_rules_title | Text | gameplay_info_card | 左对齐 | 0,继上一行 | wrap×wrap | PanelStyle.Accent | titleSmall |
| gameplay_info_section_rules_body | Text（5 行独立 Text，非拼接单段） | gameplay_info_card | 左对齐 | 0,继上一行 | wrap×wrap | labelSecondary | bodyMedium |
| enter_immersive_button | Button | main_toolbar | 工具条常驻 Row 的首个子项 | 0,0 | wrap×wrap | 默认 ButtonDefaults 配色（labelPrimary on fillPrimary） | labelLarge |
| charge_bar | Box（蓄力条，`ChargeIndicatorColor` 绿→红插值，与沉浸态指尖球同一套语义） | main_toolbar | 工具条 Playing 态 Row 内，紧邻 hud_score/hud_combo_badge | 0,0 | 120×12dp | `ChargeIndicatorColor.forFraction(...)`（非主题色角色，独立于面板配色） | - |
| blocked_message | Text | main_toolbar | enter_immersive_button 所在 Row，紧随其后 | 0,0 | wrap×wrap | labelSecondary | 默认 Text 样式 |
| exit_immersive_button | Button（`HudContent` 的 `onExitImmersive` 插槽，仅沉浸态 hud 面板传入） | hud | hud 内容行尾，score/combo 之后 | 0,0 | wrap×wrap | 默认 ButtonDefaults 配色 | 默认 Button 样式 |

> 颜色角色说明（v2）：门禁 A 增量草案曾提议 gameplay_entry_button 用 `colorScheme.fillSecondary`；门禁 A 最终确认稿改为"复用 start_button 实际使用的颜色角色（PanelStyle.Accent）并调低 alpha"，本表已按最终确认稿记录，不是草案版本。gameplay_info_section_*_title 同理，最终确认稿要求小标题用强调色（PanelStyle.Accent），不是草案版本的 labelPrimary。

## 3. 状态清单

| 元素 id | 状态 | 视觉变化 |
|---|---|---|
| start_button | hover | 背景提亮一档（Modifier.spatialHoverEffect） |
| retry_button | hover | 背景提亮一档（Modifier.spatialHoverEffect） |
| hud_combo_badge | streak=0 | 不显示（隐藏，非灰态） |
| result_title | 破纪录 | 文案变为"新纪录！"，其余不变 |
| gameplay_entry_button / gameplay_info_close_button | hover | 未显式调用 `Modifier.spatialHoverEffect`（本次实现未验证该项目此前从未用过的 API 组合），沿用 Button 组件的内置默认交互态；门禁 B 需截图/上机确认是否已有可辨的 hover 反馈 |
| gameplay_info_overlay | 显示⇄隐藏 | 淡入淡出（AnimatedVisibility + fadeIn()/fadeOut()，与 HomeStage 中 phase 切换同款过渡） |
| gameplay_info_scrim | 点击空白处（卡片以外区域） | 等效于点击 gameplay_info_close_button，关闭浮层；scrim 用 `pointerInput(Unit) { detectTapGestures { onDismiss() } }` 消费点击，不透传到下层 start_button |
| enter_immersive_button / exit_immersive_button | 禁用（v3） | `HopSession.canSwitchContainer()`（飞行中或已有一次切换在途）返回 false 时置灰不可点——飞行动画状态住在 `CharacterController` 里、不在 `HopSession` 里，与其序列化它，不如把按钮置灰 |
| enter_immersive_button | 点击 → 被系统拒绝（v3） | `enterImmersive` 返回 `SwitchOutcome.BLOCKED_BY_SYSTEM`（通常是别的应用正占用 Full Space）时，blocked_message 浮出显示；其余结果不显示任何消息，静默完成切换 |
| exit_immersive_button | 出现时机（v3，最终评审 I1 修复） | 不再随 `HopPhase` 隐藏——原先只包在 `AnimatedVisibility(visible = phase is Playing)` 里，NotStarted/Result 两态都进不去也退不出沉浸；现在 NotStarted/Playing/Result 三态全程可点。同一 hud 面板内的 hud_score/hud_combo_badge 仍然只在 Playing 态显示（`HudContent` 内部按 phase 分支），两者互不联动 |
| exit_immersive_button | 点击 → 切换结果非 SWITCHED（v3，最终评审 I2 修复） | `exitImmersive` 的返回值不再被丢弃——非 `SwitchOutcome.SWITCHED` 时打一条 `Log.e`（tag `SpaceHopImmersive`），与 `WindowHome.kt` 的 `openImmersiveStage` 失败日志同一惯例；本次未新增用户可见的失败提示 UI，与 enter_immersive_button 侧的 blocked_message 不完全对称 |

## 4. 文案清单

| 元素 id | 文案 |
|---|---|
| start_title | SpaceHop |
| start_best_score | 历史最高 {n} |
| start_button | 开始游戏 |
| hud_score | {score} |
| hud_combo_badge | 连击 x{streak} |
| result_title | 本局结束 / 新纪录！ |
| result_score | {score} |
| result_best_combo | 最高连击 x{n} |
| result_best_score | 历史最高 {n} |
| retry_button | 再来一局 |
| gameplay_entry_button | 玩法（leadingIcon 为纯文本"?"占位徽标，不是图标资源） |
| gameplay_info_title | 玩法说明 |
| gameplay_info_close_button | × |
| gameplay_info_section_intro_title | 简介 |
| gameplay_info_section_intro_body | 你要操控一只从厨房水缸里逃出来的青蛙,靠捏合蓄力、松开起跳,一路跳过一个又一个盘子,尽量跳得又准又远,不断刷新自己的历史最高分。 |
| gameplay_info_section_controls_title | 操作 |
| gameplay_info_section_controls_body | - 蓄力起跳：拇指和食指靠拢开始蓄力,松开手指就会起跳,蓄的时间越久跳得越远,直到大约1.2秒封顶。习惯用手柄的话,按住任意一侧扳机同样能蓄力,松开就起跳,效果跟捏合完全一样。 |
| gameplay_info_section_rules_title | 规则 |
| enter_immersive_button | 进入沉浸 |
| exit_immersive_button | 退出沉浸 |
| blocked_message | 另一个应用正占用沉浸空间，稍后再试 |
| gameplay_info_section_rules_body | 1. 捏合(或按住扳机)开始蓄力,松手就起跳——起跳方向永远自动瞄准下一个盘子,你唯一要拿捏的是"蓄力多久"。 2. 落地会有三种结果:卡得刚刚好是"完美",差一点点也在容许范围内算"成功",偏差太大就是"落空"。落空会立刻结束这一局,直接弹出结算,不会问你要不要再试一次。 3. 刚开始的厨房阶段,每一跳都有大约10秒的隐形倒计时。 4. 连续跳上一阵子(或者跳得又快又稳),会触发一次特别的"穿窗跳跃",带青蛙跳出厨房、跳进荷花池。 5. 荷花池里没有倒计时压力,但盘子会越跳越远、容许的误差也越来越小,拼的完全是手感。 |

> gameplay_info_section_controls_body / gameplay_info_section_rules_body 在实现中各自渲染成独立的多行 `Text`（操作 1 行，规则 5 行），上表压成一格只是为了表格紧凑；原文每行的行首前缀（"- "、"1."–"5."）在实现里逐字保留在对应行首，文案本身未做任何改写/转述/精简（详见 StartPageContent.kt 的 `GAMEPLAY_INTRO_TEXT` / `GAMEPLAY_CONTROLS_TEXT` / `GAMEPLAY_RULES_LINES`）。

## 5. 不做清单

- 无落点预告 UI
- 无特殊主题平台的专属面板
- 无皮肤/金币相关 UI
- 无摔落确认弹窗——落空直接呈现 result_panel
- 无场景复位按钮——v1 不含场景移动/旋转/缩放手势本体，复位按钮待该手势落地后随 v2 契约增量一并加入，本版本不做一个按下去没有对应效果的按钮
- （v2）不新增图标 drawable 资源——gameplay_entry_button / gameplay_info_close_button 均用纯文本占位（"?"/"×"），不引入 `androidx.compose.material.icons`
- （v2）玩法浮层不做"裸手 vs 控制器"专属操作说明分支——操作段落原文已把两种输入方式写进同一段
- （v2）不做浮层内的二级滚动/分页——简介/操作/规则三段一次性放进同一个可滚动 Column
- （v2）不改 HomeStage.kt 的 HopPhase 状态机、不新增 AttachmentPanel——浮层显隐只是 StartPageContent 内部一个局部布尔状态
- （v3）不做窗口态/沉浸态切换的二次确认弹窗——进入沉浸、退出沉浸都不经确认框；沉浸态唯一的确认框（ExitConfirmContent）只由系统返回键触发退出 App，与容器切换无关，是既有行为，不在本增量新增范围
- （v3）exit_immersive_button 侧不做与 enter_immersive_button 对称的 blocked_message 用户可见提示——最终评审 I2 的最低要求只是"失败必须有日志"，本增量只加了 `Log.e`，没有再造一套 UI 去凑对称

## 6. 截图验证计划

| 序号 | 状态描述 | 到达方式 | 本图核对的元素 id |
|---|---|---|---|
| 1 | 冷启动默认态 | 启动后等待渲染 6s | start_title, start_best_score, start_button |
| 2 | 游玩中态 | 点击 start_button 进入 Playing | hud_score, hud 面板未遮挡舞台 |
| 3 | 结算态 | 临时调试触发（DEBUG 强制落空按钮）强制进入 Result | result_title, result_score, result_best_combo, result_best_score, retry_button |
| 4 | 玩法入口态（v2） | 冷启动等待渲染后直接截图 | gameplay_entry_button 在右上角、颜色与 start_button 同色系但更浅淡，不遮挡/不被 start_title 顶到 |
| 5 | 玩法浮层展开态（v2） | 点击 gameplay_entry_button | gameplay_info_scrim（背景是否压暗）、gameplay_info_card、gameplay_info_title、gameplay_info_close_button、简介/操作/规则三段文案是否被裁切或超出可滚动区域 |
| 6 | 浮层关闭回归态（v2） | 点击 gameplay_info_close_button 或点击 scrim 空白处 | 确认回到状态 1（start_title/start_best_score/start_button 原样可见，浮层完全消失，且 start_button 没有出现"幽灵 hover"高亮） |
| 7 | 窗口态工具条常驻态（v3） | 窗口态冷启动（未开局）与结算态分别截图 | enter_immersive_button 在起始页、结算页均可见可点，不需要先开一局才能换容器；已在门禁 B 完成真机验证，见 task-10-report.md |
| 8 | 沉浸态退出按钮常驻态（v3，最终评审 I1） | 沉浸态冷启动（未开局，NotStarted）与结算态（Result）分别截图 | exit_immersive_button 在这两态下均可见可点；hud_score/hud_combo_badge 在这两态下均不显示，只在 Playing 态出现 |
