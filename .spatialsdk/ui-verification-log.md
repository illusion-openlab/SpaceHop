# SpaceHop UI 验证日志

## 第 1 轮 — 2026-08-13 — 静态舞台(门禁 B, Task 6)

- 截图:`.spatialsdk/ui-verify/round1-static-stage.png`(emulator-5554)
- 判据:当前平台 + 下一个平台 + 角色占位完整可见,不被 FOV 裁切
- 结果:**通过**。三者均在画面内,无裁切。下一个平台明显比当前平台小,符合"更远"的近大远小预期。颜色对比清晰(平台蓝 vs 背景 vs 角色橙)。
- 观察(非 Blocker,留待后续验证):两个平台在这个视角下垫得很近、角色夹在中间,读起来不够像"脚下一个、前方还有一个"。推测是纯透视效果(横向偏移只有 ±8cm,纵深却退后 18~32cm,近大远小压缩了视觉分离度),不是几何 bug。等 Task 7/8 角色会动、有拖尾之后再复查这个观感;若仍然不好读,候选修法是加大下一个平台的横向偏移范围,或把 `BOARD_DISTANCE_M`/摆放角度调整得更贴近design doc"微俯视角"的预期效果。
- 头显标定:本轮截图对应的运行没有额外记录 `heightAdjustM` 的 logcat 输出;摆放位置的绝对高度舒适度需要真机验证(模拟器的默认头显姿态不代表真实用户视角),留待用户试玩时确认。

## 第 2 轮 — 2026-08-13 — 无限滚动(门禁 B, Task 8)

- 首次 `pico-cli app launch tech.illusion.spacehop` 报错:`ADB start activity did not create a running process`,截图显示前台是 `SpaceGomoku`(另一个并行会话的应用)——按 [[shared-emulator-across-sessions]] 记录的模式,先怀疑是设备争用,不是自己的 bug
- 诊断(锁内、快进快出,未 force-stop 任何人):`adb shell pidof tech.illusion.spacehop` 返回真实 PID;`adb logcat` 里没有任何 `FATAL EXCEPTION`,只看到正常的 `ContainerManager`/`WorldSpace`/`SpatialRuntimeService: Watchdog: focus=34(tech.illusion.spacehop) lastRenderTime=...` 持续递增(7+ 秒),证明进程健康运行且在渲染
- 结论:`pico-cli app launch` 自带的"进程已创建"校验在这台设备上(同时挂着 SpaceHop / SpaceGomoku / SpaceLianliankan 三个已注册的 spatial 应用,负载较高)存在**误报的假阴性**,不是应用崩溃或代码 bug。重新截图确认:当前平台/下一个平台/角色三者渲染正常,构图与第 1 轮一致
- 截图:`.spatialsdk/ui-verify/round2-diag.png`
- 结果:**通过**(平台/角色渲染正确,进程健康)。记录此 `pico-cli` 假阴性现象,供后续会话在这台设备负载高时参考——遇到同样报错先查 `pidof` + `logcat` 里的 `FATAL EXCEPTION`,不要立即当成崩溃处理
- 无限模式的实际手感(蓄力精度、连击、摔落结束的观感、`animateAdvance` 的滑动/淡入观感)仍未真机验证,留给用户试玩确认

## 第 3 轮 — 2026-08-13 — 三块面板(门禁 B, Task 10)

### 第 1 次尝试:确认到一个真实 Blocker

- 截图 `round3-start-page-retry.png` 清晰显示的是**本项目自己的**内容(橙色角色球 + 蓝色平台,SpaceLianliankan 完全是另一种画风,不会认错)。`start_page` 面板(灰色玻璃卡片)渲染出来了,但:
  - **Blocker**:`start_title`/`start_best_score`/`start_button` 三个元素**完全没有渲染任何文字或按钮**,只有一张空玻璃卡片
  - **Blocker**:面板与"当前平台"完全重叠(两者都在 `anchor` 局部原点,没有分开摆放),角色球和平台直接从面板里"穿"出来
- 定位:`HomeStage.kt` 里 `attachments.entity(PRIMARY_PANEL)?.let { anchor.addChild(it) }`从未给面板设置独立位置,默认落在锚点原点——跟"当前平台"的位置完全一样。这很可能是文字消失的根因:面板和不透明的 3D 几何体挤在同一深度,深度测试冲突下面板的文字层被遮住
- 修复:给 `PRIMARY_PANEL` 加一个朝玩家方向的 Z 偏移(`PRIMARY_PANEL_FORWARD_OFFSET_M = 0.3f`),让它摆在舞台正前方、玩家和平台之间(呼应设计文档原意),不再跟平台共享同一个位置

### 第 2 轮验证:设备持续被其它会话占用,未能拿到确认结果

- 修复后重新编译(`BUILD SUCCESSFUL`),连续 3 次尝试上机确认,但这台模拟器这段时间被其它会话(至少 `SpaceGomoku`、`SpaceLianliankan`)高频占用:
  - 尝试 1:`pico-cli app launch` 报错;截图拍到的是 `SpaceLianliankan` 的关卡界面(对方会话的窗口在前台)
  - 尝试 2:改用 `logcat` 里 `pkg=...,visb=true,focus=true` 这行确认焦点归属——本次没有任何应用拿到焦点(`resolved focus package` 为空),跳过截图
  - 尝试 3:`adb shell pidof` 直接查不到进程(`NO_PID`)——这次是真的没启动起来,不只是焦点被抢
- 按"不要死等、不要连续重试"的原则,到此停止,不继续抢锁。**修复已提交,但门禁 B 第 3 轮的"面板不再被平台遮挡、文字正常显示"这一条尚未在设备上得到确认**,需要等这台模拟器空闲后补跑一次。`hud`/`result_panel` 两张截图(依赖临时调试硬改 `phase` 初始值)也还没有开始做,同样卡在设备可用性上，不是代码没写。
