package com.yfuse.harmony.entry.watch

import std.collection.*

public enum DanmakuMode { | Scroll | Top | Bottom }

public class DanmakuItem {
    public let id: String
    public let timeMs: Int64
    public let text: String
    public let colorArgb: UInt32
    public let mode: DanmakuMode
    public init(id: String, timeMs: Int64, text: String, colorArgb!: UInt32 = 0xFFFFFFFF, mode!: DanmakuMode = DanmakuMode.Scroll) {
        this.id = id; this.timeMs = timeMs; this.text = text; this.colorArgb = colorArgb; this.mode = mode
    }
}

public class DanmakuFilter {
    public var enabled: Bool = true
    public var opacity: Float64 = 0.82
    public var displayArea: Float64 = 0.5
    public var speed: Float64 = 1.0
    public var blockedWords: ArrayList<String> = ArrayList<String>()

    public func allows(item: DanmakuItem): Bool {
        if (!enabled || item.text.isEmpty()) { return false }
        for (word in blockedWords) { if (!word.isEmpty() && item.text.contains(word)) { return false } }
        return true
    }
}

public class DanmakuScheduler {
    private var nextIndex: Int64 = 0
    private var lastPositionMs: Int64 = 0

    public func reset(): Unit { nextIndex = 0; lastPositionMs = 0 }

    public func advance(positionMs: Int64, items: ArrayList<DanmakuItem>, filter: DanmakuFilter): ArrayList<DanmakuItem> {
        if (positionMs + 500 < lastPositionMs) { nextIndex = 0 }
        lastPositionMs = positionMs
        let result = ArrayList<DanmakuItem>()
        while (nextIndex < items.size) {
            let item = items[nextIndex]
            if (item.timeMs > positionMs + 100) { break }
            if (item.timeMs >= positionMs - 500 && filter.allows(item)) { result.append(item) }
            nextIndex++
        }
        return result
    }
}
