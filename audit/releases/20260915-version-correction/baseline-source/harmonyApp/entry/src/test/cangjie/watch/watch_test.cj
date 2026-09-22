package tests.watch

import std.collection.*
import std.unittest.*
import std.unittest.testmacro.*
import com.yfuse.harmony.entry.watch.*

private func correctionName(value: DriftCorrection): String {
    return match (value) {
        case DriftCorrection.None => "none"
        case DriftCorrection.SetSpeed(_, _) => "speed"
        case DriftCorrection.SeekTo(_) => "seek"
    }
}

@Test
func small_drift_is_left_alone(): Unit {
    let room = WatchRoomState()
    @Expect(correctionName(room.correction(10_000, 10_100, 1.0)), "none")
    @Expect(correctionName(room.correction(10_000, 9_900, 1.0)), "none")
}

@Test
func moderate_drift_nudges_the_speed_in_the_right_direction(): Unit {
    let room = WatchRoomState()
    // Behind the host: play slightly faster to catch up.
    let faster = match (room.correction(10_000, 11_000, 1.0)) {
        case DriftCorrection.SetSpeed(speed, holdMs) => speed > 1.0 && holdMs == 5000
        case _ => false
    }
    @Expect(faster, true)

    // Ahead of the host: play slightly slower.
    let slower = match (room.correction(11_000, 10_000, 1.0)) {
        case DriftCorrection.SetSpeed(speed, _) => speed < 1.0
        case _ => false
    }
    @Expect(slower, true)
}

@Test
func large_drift_seeks_to_the_host(): Unit {
    let room = WatchRoomState()
    let sought = match (room.correction(10_000, 30_000, 1.0)) {
        case DriftCorrection.SeekTo(position) => position == 30_000
        case _ => false
    }
    @Expect(sought, true)
}

@Test
func the_room_rejects_replayed_and_out_of_order_events(): Unit {
    let room = WatchRoomState()
    @Expect(room.accept(1), true)
    @Expect(room.accept(2), true)
    @Expect(room.accept(2), false)
    @Expect(room.accept(1), false)
    @Expect(room.accept(3), true)
}

@Test
func danmaku_filter_blocks_by_word_and_switch(): Unit {
    let filter = DanmakuFilter()
    filter.blockedWords.add("剧透")
    @Expect(filter.allows(DanmakuItem("a", 0, "好看")), true)
    @Expect(filter.allows(DanmakuItem("b", 0, "这里有剧透注意")), false)
    @Expect(filter.allows(DanmakuItem("c", 0, "")), false)

    filter.enabled = false
    @Expect(filter.allows(DanmakuItem("d", 0, "好看")), false)
}

@Test
func the_scheduler_emits_each_item_once_as_the_playhead_passes_it(): Unit {
    let items = ArrayList<DanmakuItem>()
    items.add(DanmakuItem("a", 1000, "one"))
    items.add(DanmakuItem("b", 2000, "two"))
    items.add(DanmakuItem("c", 3000, "three"))

    let scheduler = DanmakuScheduler()
    let filter = DanmakuFilter()

    @Expect(scheduler.advance(1000, items, filter).size, 1)
    @Expect(scheduler.advance(1500, items, filter).size, 0)
    @Expect(scheduler.advance(2000, items, filter).size, 1)
    @Expect(scheduler.advance(3000, items, filter).size, 1)
}

@Test
func a_jump_forward_does_not_dump_the_backlog(): Unit {
    let items = ArrayList<DanmakuItem>()
    items.add(DanmakuItem("a", 1000, "one"))
    items.add(DanmakuItem("b", 2000, "two"))

    let scheduler = DanmakuScheduler()
    let filter = DanmakuFilter()
    // Seeking straight to 2000 passes item a, but it is already outside the 500 ms window behind
    // the playhead, so only the comment that belongs on screen now is emitted.
    @Expect(scheduler.advance(2000, items, filter).size, 1)
}

@Test
func seeking_backwards_replays_the_timeline(): Unit {
    let items = ArrayList<DanmakuItem>()
    items.add(DanmakuItem("a", 1000, "one"))
    items.add(DanmakuItem("b", 2000, "two"))

    let scheduler = DanmakuScheduler()
    let filter = DanmakuFilter()
    @Expect(scheduler.advance(1000, items, filter).size, 1)
    @Expect(scheduler.advance(2000, items, filter).size, 1)
    // Nothing is left to emit until the cursor rewinds.
    @Expect(scheduler.advance(2000, items, filter).size, 0)
    // A jump back past the tolerance rewinds it, and the same comments play again.
    @Expect(scheduler.advance(0, items, filter).size, 0)
    @Expect(scheduler.advance(1000, items, filter).size, 1)
}

@Test
func blocked_items_are_skipped_without_stalling_the_cursor(): Unit {
    let items = ArrayList<DanmakuItem>()
    items.add(DanmakuItem("a", 1000, "剧透警告"))
    items.add(DanmakuItem("b", 1100, "正常"))

    let filter = DanmakuFilter()
    filter.blockedWords.add("剧透")
    let scheduler = DanmakuScheduler()
    @Expect(scheduler.advance(1200, items, filter).size, 1)
}
