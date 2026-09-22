# Android BD-J feasibility gate

## Decision

**No-Go for claiming BD-J from the current libbluray AAR. Go only for an isolated Android-specific
runtime/provider project.**

This decision keeps ordinary UHD Blu-ray main-feature and HDMV playback independent from the much
larger Java/Xlet compatibility problem.

## Why the current native build is not BD-J

The Yfuse native build intentionally sets `bdj_jar=disabled` and its capability marker reports
`BDJ=false`. This is a release contract, not a temporary UI flag.

The pinned libbluray BD-J implementation is built around a J2SE/J2ME VM integration. In
`src/libbluray/bdj/bdj.c` it owns a `JavaVM *`, searches for/loads a JVM library and resolves
`JNI_CreateJavaVM`/`JNI_GetCreatedJavaVMs`; its Java side also carries the BD-J/Xlet/AWT-compatible
classes used by the runtime. Android ART can host normal app Java/Kotlin code, but the Yfuse process
does not provide that desktop `libjvm` bootstrap contract or a complete BD-J HAVi/AWT/Xlet platform by
merely linking libbluray.

Therefore none of the following are accepted as BD-J evidence:

- `HAVE_LIBBLURAY=1`;
- HDMV menus working;
- libbluray BD-J C sources being present in the dependency tree;
- a `BDJ=true` constant added by hand;
- ordinary Android Java execution.

## Provider architecture

The common player now has a dedicated `BdJDiscSession` / `BdJDiscNavigationBackend`. It exposes only
navigation/menu state, key input, pointer input, asynchronous state changes and close. The video
engine, libbluray title reader and Android Activity do not receive a JVM/Xlet/AWT object.

The provider has the same failure rule as HDMV: one Java-runtime exception disables only the optional
BD-J provider, clears menu interception and leaves main-feature playback alive. Title/chapter ownership
remains with the engine/libbluray backend.

## Minimum Android port before BDJ=true

A future implementation must supply all of these as one independently testable module:

1. **Runtime boot** — an Android-compatible way to execute the libbluray BD-J Java stack without
   loading a desktop `libjvm`, with deterministic startup/shutdown and no process-global VM mutation.
2. **Xlet lifecycle** — application discovery plus init/start/pause/destroy semantics and clean title
   handover.
3. **BD-J API surface** — the required Java TV/HAVi/BD-J classes used by real discs; missing APIs must
   fail capability probing, not crash halfway through a film.
4. **Graphics bridge** — HScene/AWT-style graphics translated into a bounded Android overlay plane
   composited over video without blocking the decoder thread.
5. **Input bridge** — D-pad/select/back/menu plus pointer input, routed only while the BD-J application
   owns an active interactive surface.
6. **Storage sandbox** — persistent/application cache rooted inside app-private storage with per-disc
   quotas and no arbitrary filesystem access.
7. **Network sandbox** — explicit opt-in network policy, Android TLS stack, bounded timeouts and no
   inheritance of Emby/Jellyfin account credentials.
8. **Thread/process isolation** — runaway Xlet threads, uncaught exceptions and deadlocks must not
   stall playback or predictive back. A separate process is preferred if the compatibility layer can
   support it.
9. **Lifecycle evidence** — background/PiP/rotation/app process recreation and title switch tests.
10. **Disc corpus** — multiple legally available authored BD-J titles covering menus, animation,
    storage, networking and failure cases.

## Promotion gate

`MpvNativeBuildCapabilities.bdj` remains false until the exact shipping runtime passes the corpus and
its provenance is included in the release artifact. At that point Yfuse may bind a real
`BdJDiscSession` and report `DiscMenuRuntime.BdJ` only for a disc whose runtime actually initialized.

Until then HDMV menus and main-feature playback remain fully usable and the UI must not display a fake
BD-J control.
