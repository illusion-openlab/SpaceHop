# SpaceHop 设计契约

## 0. 元信息

- 应用名 / applicationId：SpaceHop / tech.illusion.spacehop
- 设计源类型：文字 PRD
- 设计源位置：docs/superpowers/specs/2026-08-13-space-hop-design.md；视觉稿 design-ref/design-mockup.html
- 契约版本：v1
- 用户确认时间：2026-08-13

## 1. 面板清单

| 面板 id | 容器类型 | 面板尺寸 | 层级/父面板 | 出现时机 |
|---|---|---|---|---|
| start_page | WindowContainer | 480×320dp | 舞台前方锚点 | 冷启动，游戏尚未开始过 |
| hud | WindowContainer | 200×180dp | 舞台侧方锚点 | 游玩中（Playing 态） |
| result_panel | WindowContainer | 320×360dp | 舞台前方锚点 | 落空后（Result 态） |

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

## 3. 状态清单

| 元素 id | 状态 | 视觉变化 |
|---|---|---|
| start_button | hover | 背景提亮一档（Modifier.spatialHoverEffect） |
| retry_button | hover | 背景提亮一档（Modifier.spatialHoverEffect） |
| hud_combo_badge | streak=0 | 不显示（隐藏，非灰态） |
| result_title | 破纪录 | 文案变为"新纪录！"，其余不变 |

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

## 5. 不做清单

- 无落点预告 UI
- 无特殊主题平台的专属面板
- 无皮肤/金币相关 UI
- 无摔落确认弹窗——落空直接呈现 result_panel
- 无场景复位按钮——v1 不含场景移动/旋转/缩放手势本体，复位按钮待该手势落地后随 v2 契约增量一并加入，本版本不做一个按下去没有对应效果的按钮

## 6. 截图验证计划

| 序号 | 状态描述 | 到达方式 | 本图核对的元素 id |
|---|---|---|---|
| 1 | 冷启动默认态 | 启动后等待渲染 6s | start_title, start_best_score, start_button |
| 2 | 游玩中态 | 点击 start_button 进入 Playing | hud_score, hud 面板未遮挡舞台 |
| 3 | 结算态 | 临时调试触发（DEBUG 强制落空按钮）强制进入 Result | result_title, result_score, result_best_combo, result_best_score, retry_button |
