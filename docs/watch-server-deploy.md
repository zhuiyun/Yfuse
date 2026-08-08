# Watch/update server deployment

`watchTogetherServer` is the combined Ktor backend behind `/watch`, `/yfuse` and the
account routes. Unlike the Android APK it has **no release workflow** — it is deployed by
hand, and this document is what that means.

> The Android release workflow (`docs/android-release.md`) does not touch this service. A
> published APK and a running backend advance independently, so a client feature that
> needs a new server message will sit inert until this deployment happens.

## What is on the server

Read off `watchTogetherServer/deploy/yfuse-watch.service`, which is the source of truth:

| | |
| --- | --- |
| Service | `yfuse-watch.service` |
| Runs as | `yfuse:yfuse` |
| Binary | `/opt/yfuse-watch/current/bin/watchTogetherServer` |
| Port | `8080`, behind Caddy — never exposed publicly |
| Update files | `/srv/yfuse-update/yfuse` (read-only to the service) |
| Account DB | `/var/lib/yfuse/account.db` (the one writable path) |

`current` is a path the unit points at rather than a build output, so the deployment shape
is "unpack a new directory, then move `current` onto it". The steps below assume it is a
symlink; if the server actually has a plain directory there, adapt step 4 — everything
else holds either way.

`ExecStart` is the launcher Gradle's `application` plugin generates, so what gets deployed
is an `installDist` tree (`bin/` + `lib/`), not a single jar.

## Deploying

### 1. Build

On any machine with JDK 17 and the repository checked out:

```bash
./gradlew :watchTogetherServer:installDist
```

Output: `watchTogetherServer/build/install/watchTogetherServer/` — verify it has both
`bin/watchTogetherServer` and a populated `lib/`.

Run the tests too; nothing downstream does it for you:

```bash
./gradlew :watchTogetherServer:test
```

### 2. Package and upload

```bash
cd watchTogetherServer/build/install
tar czf /tmp/yfuse-watch.tar.gz watchTogetherServer
scp /tmp/yfuse-watch.tar.gz <admin>@47.112.219.60:/tmp/
```

`<admin>` must be an account that can write `/opt/yfuse-watch` and restart the service.
**The `yfuse-deploy` account used by the APK workflow cannot do this** — by design it owns
only the update directory and has no sudo (`docs/android-release.md`).

### 3. Unpack beside the current release

Date-stamped so the previous one stays on disk to roll back to:

```bash
release="/opt/yfuse-watch/$(date -u +%Y%m%d-%H%M%S)"
sudo mkdir -p "$release"
sudo tar xzf /tmp/yfuse-watch.tar.gz -C "$release" --strip-components=1
sudo chown -R root:root "$release"
sudo test -x "$release/bin/watchTogetherServer"
```

### 4. Switch and restart

```bash
sudo ln -sfn "$release" /opt/yfuse-watch/current.new
sudo mv -T /opt/yfuse-watch/current.new /opt/yfuse-watch/current
sudo systemctl restart yfuse-watch
sudo systemctl status yfuse-watch --no-pager
```

`ln` + `mv -T` rather than `ln -sfn` straight onto `current`: the rename is atomic, so
there is no instant where `current` does not resolve.

Restarting drops every open watch-together socket. Clients reconnect on their own
(`reconnecting` keeps the room on screen while they do), but anyone mid-film gets a brief
resync — so prefer a quiet moment.

### 5. Verify

Service is alive:

```bash
curl --fail http://127.0.0.1:8080/health              # on the server
curl --fail https://47.112.219.60/health              # through Caddy
curl --fail https://47.112.219.60/watch/version
journalctl -u yfuse-watch -n 50 --no-pager
```

**`/watch/version` cannot tell you whether this deployment took.** It reports
`protocolVersion: 3`, and the reaction feature deliberately did not bump it — reactions are
an additive message type that old clients neither send nor receive, so nothing about the
protocol contract changed. The endpoint proves the service is up, nothing more.

To verify the reaction feature specifically, use the app: two devices (or one device and a
second account) in one room, tap a reaction in 一起看 → 聊天面板. The sender always sees
its own bubble because the client echoes locally — **only the other device seeing it proves
the server relayed it.**

### 6. Roll back

```bash
ls -la /opt/yfuse-watch/                    # find the previous timestamped release
sudo ln -sfn /opt/yfuse-watch/<previous> /opt/yfuse-watch/current.new
sudo mv -T /opt/yfuse-watch/current.new /opt/yfuse-watch/current
sudo systemctl restart yfuse-watch
```

The account database lives in `/var/lib/yfuse` and is untouched by any of this, so a
rollback loses no user data.

## If this should become a workflow

It can reuse the APK workflow's SSH plumbing, but not its credentials: `yfuse-deploy` has
neither ownership of `/opt/yfuse-watch` nor the sudo rights to restart a unit, and
widening it would also widen the account that already has write access to the published
APK path. A separate deployment account with exactly two grants — ownership of
`/opt/yfuse-watch`, and `NOPASSWD` on `systemctl restart yfuse-watch` — keeps the two
blast radiuses apart.

The build itself is the easy half; the gate worth having is the one this document cannot
provide from a runner: something that proves the new binary is the one now serving.
Stamping the build's git SHA into `/watch/version` would give a deployment check a fact to
assert, and is the piece to add before automating any of this.
