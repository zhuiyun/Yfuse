import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { RenderScheduler, withRenderContext, cancelOnDisconnect } from "./render-scheduler.mjs";

const tick = () => new Promise((resolve) => setImmediate(resolve));
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
};

test("queue is bounded and rejects excess work before invoking it", async () => {
  const scheduler = new RenderScheduler({ concurrency: 1, maxQueued: 1 });
  const blocked = deferred();
  const first = scheduler.run(() => blocked.promise);
  const second = scheduler.run(() => "second");
  await assert.rejects(scheduler.run(() => assert.fail("excess work ran")), /renderer_busy/);
  assert.equal(scheduler.active, 1);
  assert.equal(scheduler.queue.length, 1);
  blocked.resolve("first");
  assert.equal(await first, "first");
  assert.equal(await second, "second");
  await tick();
  assert.equal(scheduler.active, 0);
});

test("disconnect removes queued work and gives its space to the next caller", async () => {
  const scheduler = new RenderScheduler({ concurrency: 1, maxQueued: 1 });
  const blocked = deferred();
  const first = scheduler.run(() => blocked.promise);
  const controller = new AbortController();
  const cancelled = scheduler.run(() => assert.fail("cancelled job ran"), { signal: controller.signal });
  controller.abort(new Error("request_cancelled"));
  await assert.rejects(cancelled, /request_cancelled/);
  assert.equal(scheduler.queue.length, 0);
  const replacement = scheduler.run(() => "replacement");
  blocked.resolve();
  await first;
  assert.equal(await replacement, "replacement");
});

test("deadline includes queue time and does not free a still-running browser slot", async () => {
  const scheduler = new RenderScheduler({ concurrency: 1, timeoutMs: 25 });
  const blocked = deferred();
  const first = scheduler.run(() => blocked.promise);
  const second = scheduler.run(() => assert.fail("expired queued work ran"));
  await Promise.all([
    assert.rejects(first, /render_timeout/),
    assert.rejects(second, /render_timeout/),
  ]);
  assert.equal(scheduler.queue.length, 0);
  assert.equal(scheduler.active, 1);
  blocked.resolve();
  await tick();
  assert.equal(scheduler.active, 0);
});

test("deadline closes active context and cleanup failure cannot leak its scheduler slot", async () => {
  const scheduler = new RenderScheduler({ concurrency: 1, timeoutMs: 25 });
  const navigation = deferred();
  let closeCount = 0;
  const browser = { newContext: async () => ({
    close: async () => { closeCount++; navigation.resolve(); throw new Error("close_failed"); },
  }) };
  await assert.rejects(scheduler.run((signal) => withRenderContext(
    browser, {}, signal, () => navigation.promise,
  )), /render_timeout/);
  await tick();
  assert.equal(closeCount, 1);
  assert.equal(scheduler.active, 0);
  assert.equal(await scheduler.run(() => "recovered"), "recovered");
});

test("context returned after cancellation is closed without starting a render", async () => {
  const scheduler = new RenderScheduler({ concurrency: 1 });
  const creation = deferred();
  const controller = new AbortController();
  let closeCount = 0;
  let renderCount = 0;
  const browser = { newContext: () => creation.promise };
  const running = scheduler.run((signal) => withRenderContext(
    browser, {}, signal, () => { renderCount++; },
  ), { signal: controller.signal });
  await tick();
  controller.abort(new Error("request_cancelled"));
  await assert.rejects(running, /request_cancelled/);
  // Hold the slot while creation is outstanding, then clean up the late context exactly once.
  assert.equal(scheduler.active, 1);
  creation.resolve({ close: async () => { closeCount++; } });
  await tick();
  assert.equal(renderCount, 0);
  assert.equal(closeCount, 1);
  assert.equal(scheduler.active, 0);
  assert.equal(await scheduler.run(() => "recovered"), "recovered");
});

test("disconnect aborts running navigation; normal HTTP completion does not cancel", async () => {
  const request = new EventEmitter();
  const response = Object.assign(new EventEmitter(), { writableEnded: false });
  const lifetime = cancelOnDisconnect(request, response);
  const navigation = deferred();
  const browser = { newContext: async () => ({ close: async () => navigation.resolve() }) };
  const rendering = withRenderContext(browser, {}, lifetime.signal, () => navigation.promise);
  await tick();
  response.emit("close");
  await assert.rejects(rendering, /request_cancelled/);
  lifetime.dispose();
  assert.equal(request.listenerCount("aborted"), 0);
  assert.equal(response.listenerCount("close"), 0);
  const completed = cancelOnDisconnect(request, response);
  response.writableEnded = true;
  response.emit("close");
  assert.equal(completed.signal.aborted, false);
  completed.dispose();
});
