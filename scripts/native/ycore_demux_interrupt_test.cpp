#include "ycore_demux_interrupt.h"
#include <cassert>

int main() {
    YCoreDemuxInterrupt state;
    assert(state.request(1));
    assert(state.pending());
    assert(state.resume(1));
    assert(!state.pending());
    assert(state.request(3));
    assert(!state.request(2));
    assert(!state.resume(1));
    assert(state.pending());
    assert(state.resume(3));
    state.cancelled.store(true);
    assert(state.request(4));
    assert(!state.resume(4));
    assert(state.cancelled.load());
    assert(state.pending());
}
