const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function receiver() {
  const listeners = new Map();
  const interceptors = new Map();
  const messages = [];
  const profile = { videoCodec: 'dvhe.05.06', audioCodec: 'ec-3', dolbyVision: true, dolbyAtmos: true };
  const mediaFor = (revision) => ({
    contentId: 'https://example.invalid/movie-' + revision + '.mp4',
    customData: { yfuseRevision: revision, yfuseProfile: profile },
  });
  let media = mediaFor(1);
  const player = {
    getMediaInformation: () => media,
    getAudioTracksManager: () => ({ getActiveTrack: () => ({
      trackId: 1, trackContentType: 'audio/ec-3; spatialRendering=true',
    }) }),
    getTextTracksManager: () => ({ getActiveTracks: () => [] }),
    addEventListener: (type, fn) => listeners.set(type, [...(listeners.get(type) || []), fn]),
    setMessageInterceptor: (type, fn) => interceptors.set(type, fn),
  };
  const context = {
    getPlayerManager: () => player,
    getDeviceCapabilities: () => ({ is_dv_supported: true, is_dolby_atmos_supported: true }),
    canDisplayType: () => true,
    sendCustomMessage: (_, __, message) => messages.push(message),
    addCustomMessageListener: () => {},
    start: () => {},
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'receiver.js'), 'utf8'), {
    cast: { framework: {
      CastReceiverContext: { getInstance: () => context }, CastReceiverOptions: function () {},
      events: { EventType: {
        PLAYING: 'PLAYING', MEDIA_STATUS: 'MEDIA_STATUS', ERROR: 'ERROR', PLAYER_LOAD_COMPLETE: 'LOAD_COMPLETE',
      } },
      messages: { HdrType: { DV: 'DV' }, PlayerState: { PLAYING: 'PLAYING' }, MessageType: { LOAD: 'LOAD' } },
    } },
    document: { getElementById: () => null },
  });
  const emit = (type, event = {}) => listeners.get(type).forEach((fn) => fn(event));
  return {
    messages, emit,
    receipt: () => messages.filter((message) => message.type === 'output.receipt').at(-1),
    load(revision, intercept = true) {
      media = mediaFor(revision);
      if (intercept) interceptors.get('LOAD')({ media });
    },
    status(revision, hdrType = 'DV') {
      emit('MEDIA_STATUS', { mediaStatus: {
        playerState: 'PLAYING', videoInfo: { hdrType },
        ...(revision == null ? {} : { media: mediaFor(revision) }),
      } });
    },
  };
}

test('new LOAD cannot reuse previous Dolby evidence before its own media status', () => {
  const app = receiver();
  app.status(1);
  assert.equal(app.receipt().dolbyVisionOutput, true);
  assert.equal(app.receipt().dolbyAtmosOutput, true);
  app.load(2);
  app.emit('PLAYING');
  assert.equal(app.receipt().revision, 2);
  assert.equal(app.receipt().playbackConfirmed, false);
  assert.equal(app.receipt().dolbyVisionOutput, false);
  assert.equal(app.receipt().dolbyAtmosOutput, false);
  app.status(2);
  assert.equal(app.receipt().playbackConfirmed, true);
  assert.equal(app.receipt().dolbyVisionOutput, true);
});

test('late and unscoped statuses do not confirm current output or suppress session state', () => {
  const app = receiver();
  app.load(2);
  app.status(1);
  app.status(null);
  app.emit('PLAYING');
  assert.equal(app.receipt().playbackConfirmed, false);
  assert.equal(app.receipt().dolbyVisionOutput, false);
  assert.equal(app.messages.filter((message) => message.type === 'session.state').length, 2);
  app.status(2, 'HDR10');
  assert.equal(app.receipt().playbackConfirmed, true);
  assert.equal(app.receipt().dolbyVisionOutput, false);
  app.status(1, 'DV');
  app.emit('PLAYING');
  assert.equal(app.receipt().dolbyVisionOutput, false);
});

test('automatic queue advance is scoped even without a LOAD interceptor callback', () => {
  const app = receiver();
  app.status(1);
  app.load(2, false);
  app.emit('PLAYING');
  assert.equal(app.receipt().dolbyVisionOutput, false);
  app.status(2);
  assert.equal(app.receipt().dolbyVisionOutput, true);
  app.emit('ERROR');
  assert.equal(app.receipt().playbackConfirmed, false);
  assert.equal(app.receipt().dolbyVisionOutput, false);
  assert.equal(app.receipt().dolbyAtmosOutput, false);
});

test('reloading the same revision clears previous playback evidence', () => {
  const app = receiver();
  app.status(1);
  app.load(1);
  app.emit('PLAYING');
  assert.equal(app.receipt().playbackConfirmed, false);
});
