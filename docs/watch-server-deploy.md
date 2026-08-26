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
| Service | `yfuse-update.service` |
| Runs as | `yfuse:yfuse` |
| Binary | `/opt/yfuse-watch/current/bin/watchTogetherServer` |
| Port | `8080`, behind Caddy — never exposed publicly |
| Update files | `/srv/yfuse-update/yfuse` (read-only to the service) |
| Account DB | `/var/lib/yfuse/account.db` |
| Calendar DB | `/var/lib/yfuse/calendar.db` (public schedule revisions only) |

The repository template is named `deploy/yfuse-watch.service` for clarity, but production
installs it as `/etc/systemd/system/yfuse-update.service` to preserve the existing unit identity.
Do not start a second `yfuse-watch.service`; both units would contend for port 8080.

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
release="/opt/yfuse-watch/releases/$(date -u +%Y%m%d-%H%M%S)-<git-sha>-v6"
sudo mkdir -p "$release"
sudo tar xzf /tmp/yfuse-watch.tar.gz -C "$release" --strip-components=1
sudo chown -R root:root "$release"
sudo test -x "$release/bin/watchTogetherServer"
```

Before the first deployment that enables six-digit migration, create the required
root-only environment file. The relay master key is exactly 32 random bytes encoded as
unpadded base64url; it must never be committed or printed in routine logs:

```bash
sudo install -d -o root -g root -m 0700 /etc/yfuse-watch
key="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
printf 'MIGRATION_RELAY_MASTER_KEY=%s\n' "$key" |
  sudo tee /etc/yfuse-watch/environment >/dev/null
unset key
sudo chown root:root /etc/yfuse-watch/environment
sudo chmod 0600 /etc/yfuse-watch/environment
sudo test "$(stat -c '%a:%U:%G' /etc/yfuse-watch/environment)" = "600:root:root"
```

The installed `yfuse-update.service` declares this `EnvironmentFile` without an optional `-` prefix: a
missing key is a deployment error and the service must fail closed. Back up this key in the
operator's secret store. Do not rotate it while an unexpired migration code is outstanding;
after rotation, restart the service and treat all earlier codes as invalid.

For the first `zhuiyun` registration only, generate a separate high-entropy bootstrap invite
and append it to the same protected file. Do not commit it or reuse a human six-digit code:

```bash
bootstrap_invite="$(openssl rand -base64 24 | tr '+/' '-_' | tr -d '=\n')"
printf 'ACCOUNT_REGISTRATION_INVITE_CODES=%s\n' "$bootstrap_invite" |
  sudo tee -a /etc/yfuse-watch/environment >/dev/null
printf '%s\n' "$bootstrap_invite" | sudo tee /root/yfuse-zhuiyun-bootstrap-invite.txt >/dev/null
unset bootstrap_invite
sudo chmod 0600 /etc/yfuse-watch/environment /root/yfuse-zhuiyun-bootstrap-invite.txt
```

After `zhuiyun` registers and the App confirms the `invite:issue` capability, remove only the
`ACCOUNT_REGISTRATION_INVITE_CODES` line and its root-only handoff file, then restart
`yfuse-update`. The database also atomically marks the static invite as consumed; removing the
environment copy minimizes secret residency.

### 4. Switch and restart

Before switching a build that can change persistence, take online SQLite snapshots of the account
and public calendar databases. Both use WAL, so do not copy only the main `.db` files while the
service is running:

```bash
stamp="$(date -u +%Y%m%d-%H%M%S)"
account_backup="/var/lib/yfuse/backups/account-$stamp.db"
calendar_backup="/var/lib/yfuse/backups/calendar-$stamp.db"
sudo install -d -o yfuse -g yfuse -m 0700 /var/lib/yfuse/backups
sudo -u yfuse sqlite3 /var/lib/yfuse/account.db ".backup '$account_backup'"
sudo -u yfuse sqlite3 /var/lib/yfuse/calendar.db ".backup '$calendar_backup'"
sudo -u yfuse sqlite3 "$account_backup" "PRAGMA integrity_check;" | grep -Fx ok
sudo -u yfuse sqlite3 "$calendar_backup" "PRAGMA integrity_check;" | grep -Fx ok
```

Retain at least the newest known-good snapshot off-host according to the operator's
recovery policy. A binary rollback does not undo a future schema/data migration; restore
the matching verified snapshot only during an explicit recovery window.

```bash
sudo ln -sfn "$release" /opt/yfuse-watch/current.new
sudo mv -T /opt/yfuse-watch/current.new /opt/yfuse-watch/current
sudo systemctl daemon-reload
sudo systemctl restart yfuse-update
sudo systemctl status yfuse-update --no-pager
sudo systemctl show yfuse-update -p EnvironmentFiles --no-pager
sudo systemctl show yfuse-update \
  -p LimitNOFILE -p TasksMax -p MemoryHigh -p MemoryMax --no-pager
```

`ln` + `mv -T` rather than `ln -sfn` straight onto `current`: the rename is atomic, so
there is no instant where `current` does not resolve.

Restarting drops every open watch-together socket. Clients reconnect on their own
(`reconnecting` keeps the room on screen while they do), but anyone mid-film gets a brief
resync — so prefer a quiet moment.

The unit does not become active until `ExecStartPost` can reach the loopback `/health`
endpoint. It allows up to 45 seconds for JVM startup, raises the file-descriptor limit
to 16,384 for WebSockets, caps tasks at 256, and applies `MemoryHigh=256M` /
`MemoryMax=384M` around the existing `-Xmx128m` heap. A readiness failure is therefore
a failed start and is handled by the unit's bounded restart policy instead of exposing
a half-started backend through Caddy.

### 5. Verify

Service is alive:

```bash
curl --fail http://127.0.0.1:8080/health              # on the server
curl --fail https://47.112.219.60/health              # through Caddy
curl --fail https://47.112.219.60/watch/version
test "$(curl -sS -o /dev/null -w '%{http_code}' http://47.112.219.60/watch)" = 426
journalctl -u yfuse-update -n 50 --no-pager
```

`/watch/version` must report `protocolVersion: 6` and `minProtocolVersion: 5`. Version 6 keeps
the authenticated v5 wire shape so the server can be deployed first while installed v5 clients
continue to create, join, and reconnect. Both versions require a valid Yfuse account access token,
bind membership to the authenticated user id, and retain authenticated resume, host capabilities,
strict wire validation, and session-generation checks. Version 4 predates mandatory account
authentication and remains rejected. Deploy and verify the v6 server before publishing a v6
client, and keep the minimum at v5 until the installed v5 population has aged out.

The legacy HTTP site may serve only old update metadata and APKs. Its `/api/*` and `/watch`
matchers must return `426` before the catch-all reverse proxy, so access tokens and watch-room
WebSocket upgrades cannot cross a plaintext public hop.

To verify the reaction feature specifically, use the app: two devices (or one device and a
second account) in one room, tap a reaction in 一起看 → 聊天面板. The sender always sees
its own bubble because the client echoes locally — **only the other device seeing it proves
the server relayed it.**

### 6. Roll back

```bash
ls -la /opt/yfuse-watch/releases/           # find the previous timestamped release
sudo ln -sfn /opt/yfuse-watch/releases/<previous> /opt/yfuse-watch/current.new
sudo mv -T /opt/yfuse-watch/current.new /opt/yfuse-watch/current
sudo systemctl restart yfuse-update
```

The account database lives in `/var/lib/yfuse` and is untouched by any of this, so a
rollback loses no user data.

## If this should become a workflow

It can reuse the APK workflow's SSH plumbing, but not its credentials: `yfuse-deploy` has
neither ownership of `/opt/yfuse-watch` nor the sudo rights to restart a unit, and
widening it would also widen the account that already has write access to the published
APK path. A separate deployment account with exactly two grants — ownership of
`/opt/yfuse-watch`, and `NOPASSWD` on `systemctl restart yfuse-update` — keeps the two
blast radiuses apart.

The build itself is the easy half; the gate worth having is the one this document cannot
provide from a runner: something that proves the new binary is the one now serving.
Stamping the build's git SHA into `/watch/version` would give a deployment check a fact to
assert, and is the piece to add before automating any of this.
