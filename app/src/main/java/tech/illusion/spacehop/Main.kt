package tech.illusion.spacehop

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import tech.illusion.spacehop.content.IMMERSIVE_STAGE_ID
import tech.illusion.spacehop.content.ImmersiveHome
import tech.illusion.spacehop.content.WindowHome

/**
 * 两个容器都在启动时注册。
 *
 * 体积窗口是入口（manifest 里配置的就是它）；Stage 在这里按 id 声明，但直到玩家点「进入沉浸」
 * 才真正打开。只有*默认*容器在 manifest 配置，次级 Stage 必须在 DSL 里声明并按 id 寻址。
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer { PicoTheme { WindowHome() } }
        Stage(id = IMMERSIVE_STAGE_ID) { PicoTheme { ImmersiveHome() } }
    }
