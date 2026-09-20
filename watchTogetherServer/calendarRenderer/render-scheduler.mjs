// The deadline includes queue time. Cancellation settles the HTTP caller promptly, but a
// running job retains its slot until its browser context has finished cleaning up.
export class RenderScheduler {
  constructor({ concurrency = 2, maxQueued = 8, timeoutMs = 25_000 } = {}) {
    this.concurrency = concurrency;
    this.maxQueued = maxQueued;
    this.timeoutMs = timeoutMs;
    this.active = 0;
    this.queue = [];
  }

  run(task, { signal } = {}) {
    if (signal?.aborted) return Promise.reject(signal.reason);
    if (this.active >= this.concurrency && this.queue.length >= this.maxQueued) {
      return Promise.reject(new Error("renderer_busy"));
    }
    return new Promise((resolve, reject) => {
      const controller = new AbortController();
      const job = { task, controller, resolve, reject, started: false };
      const abort = () => {
        if (!job.started) {
          this.queue = this.queue.filter((queued) => queued !== job);
          job.cleanup();
        }
        reject(controller.signal.reason);
      };
      const cancel = () => controller.abort(signal.reason);
      const timer = setTimeout(() => controller.abort(new Error("render_timeout")), this.timeoutMs);
      job.cleanup = () => {
        clearTimeout(timer);
        signal?.removeEventListener("abort", cancel);
        controller.signal.removeEventListener("abort", abort);
      };
      controller.signal.addEventListener("abort", abort, { once: true });
      signal?.addEventListener("abort", cancel, { once: true });
      if (this.active < this.concurrency) this.start(job);
      else this.queue.push(job);
    });
  }

  start(job) {
    job.started = true;
    this.active += 1;
    Promise.resolve().then(() => {
      job.controller.signal.throwIfAborted();
      return job.task(job.controller.signal);
    }).then(job.resolve, job.reject).finally(() => {
      job.cleanup();
      this.active -= 1;
      const next = this.queue.shift();
      if (next) this.start(next);
    });
  }
}

// Browser operations do not accept AbortSignal. Closing the context interrupts navigation,
// response reads and evaluate calls; disposal errors still propagate to the scheduler's finally.
export async function withRenderContext(browser, options, signal, render) {
  let context;
  let closing;
  const close = () => {
    if (context && !closing) closing = Promise.resolve().then(() => context.close());
    return closing;
  };
  const cancel = () => { close()?.catch(() => {}); };
  try {
    signal.throwIfAborted();
    context = await browser.newContext(options);
    signal.addEventListener("abort", cancel, { once: true });
    signal.throwIfAborted();
    const result = await render(context);
    signal.throwIfAborted();
    return result;
  } finally {
    signal.removeEventListener("abort", cancel);
    await close();
  }
}

export function cancelOnDisconnect(request, response) {
  const controller = new AbortController();
  const abort = () => controller.abort(new Error("request_cancelled"));
  const closed = () => { if (!response.writableEnded) abort(); };
  request.once("aborted", abort);
  response.once("close", closed);
  return {
    signal: controller.signal,
    dispose() {
      request.removeListener("aborted", abort);
      response.removeListener("close", closed);
    },
  };
}
