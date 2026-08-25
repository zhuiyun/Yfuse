package com.yfuse.harmony.entry

import ohos.ability.*
import ohos.base.*

class MainAbility <: Ability {
    public init() {
        super()
        registerSelf()
    }

    public override func onCreate(want: Want, launchParam: LaunchParam): Unit {
        AppLog.info("Yfuse MainAbility created")
    }

    public override func onWindowStageCreate(windowStage: WindowStage): Unit {
        windowStage.loadContent("EntryView")
    }

    public override func onDestroy(): Unit {
        AppLog.info("Yfuse MainAbility destroyed")
    }
}
