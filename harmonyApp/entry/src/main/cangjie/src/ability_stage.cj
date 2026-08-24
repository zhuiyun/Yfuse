package com.yfuse.harmony.entry

import ohos.ability.*
import ohos.base.*

class YfuseAbilityStage <: AbilityStage {
    public init() {
        super()
        registerSelf()
    }

    public override func onCreate(): Unit {
        AppLog.info("Yfuse AbilityStage created")
    }
}
