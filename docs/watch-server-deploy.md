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
| Watch state DB | `/var/lib/yfuse/watch-state.db` (rooms and 手机遥控 pairing tokens) |

The repository template is named `deploy/yfuse-watch.service` for clarity, but production
installs it as `/etc/systemd/system/yfuse-update.service` to preserve the existing unit identity.
Do not start a second `yfuse-watch.service`; both units would contend for port 8080.

Watch rooms and 手机遥控 pairing tokens are written to the watch state database every two
seconds and on shutdown, so a deploy no longer ends every room: rooms saved within the last
30 minutes are restored at start, members rejoin them with the capabilities their apps kept,
and the host has the usual 20-second grace to come back before a member who was already in the
room takes over. Only capability digests are stored, never a capability or token. Deleting the
file is safe; it only ends the rooms.

`current` is a path the unit points at rather than a build output, so the deployment shape
is "unpack a new directory, then move `current` onto it". The steps below assume it is a
symlink; if the server actually has a plain directory there, adapt step 4 — everything
else holds either way.

`ExecStart` is the launcher Gradle's `application` plugin generates, so what gets deployed
is an `installDist` tree (`bin/` + `lib/`), not a single jar.

The unit runs the JVM with `-XX:+ExitOnOutOfMemoryError`, so an out-of-memory error restarts the
service instead of leaving it half alive, and keeps one heap dump at
`/var/lib/yfuse/watch-oom.hprof` (never overwritten; delete it once diagnosed, and treat it as
secret — it holds whatever was in memory). The unit is sandboxed down to the `@system-service`
system calls; if a JDK upgrade ever trips the filter, the call fails with `EPERM` and shows in
`journalctl -u yfuse-update`.

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

`TMDB_TOKEN`, when present in the same file, serves both calendar ingestion and the signed-in
app's TMDB proxy; without it the proxy answers `503` (`docs/tmdb-proxy.md`).

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
sudo -u yfuse sqlite3 "$account_backup" "PRAGMA integrity_check;" | grep -Fx ok
if sudo test -f /var/lib/yfuse/calendar.db; then
  sudo -u yfuse sqlite3 /var/lib/yfuse/calendar.db ".backup '$calendar_backup'"
  sudo -u yfuse sqlite3 "$calendar_backup" "PRAGMA integrity_check;" | grep -Fx ok
fi
```

The same snapshot runs nightly once the timer is installed, so a deploy never starts from a
box that has no recent backup. See [Backups](#backups) for the one-time setup of the backup
user, encryption and the off-host copy.

A binary rollback does not undo a future schema/data migration; restore the matching verified
snapshot only during an explicit recovery window.

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
to 16,384 for WebSockets, caps tasks at 256, and applies `MemoryHigh=384M` /
`MemoryMax=448M` around the `-Xmx256m` heap. The larger heap leaves bounded headroom for
streaming-provider schedule ingestion. A readiness failure is therefore
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

`/watch/version` also carries `gitSha`, stamped at build time from the checkout that produced
the jar. Compare it with the commit you meant to deploy before calling the release done:

```bash
test "$(curl -sS https://47.112.219.60/watch/version | jq -r .gitSha)" = "$(git rev-parse HEAD)"
```

Process metrics are at `/watch/metrics` in Prometheus text format, on the box only. Caddy runs
on the same host, so every request it forwards reaches the service from `127.0.0.1`; a loopback
check alone would make the endpoint public. Two rules keep it private:

- Caddy answers `/watch/metrics*` with `404` on every site, so it is never proxied.
- Without `WATCH_METRICS_TOKEN` the service answers only a loopback caller that sent no proxy
  header (`X-Forwarded-For`, `Forwarded`, `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Real-IP`):
  `curl http://127.0.0.1:8080/watch/metrics` on the box. With `WATCH_METRICS_TOKEN` (at least 16
  characters, in `/etc/yfuse-watch/environment`) every caller, local or not, must send
  `Authorization: Bearer …`; use that for an on-box scraper.

Every HTTP request and socket lifetime is logged
as one structured line on stderr (`journalctl -u yfuse-update`), with the path but never the
query string or any token.

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

To verify the reaction feature specifically, use the app: two devices in one room, using
the same account or separate accounts, and tap a reaction in 一起看 → 聊天面板. The sender always sees
its own bubble because the client echoes locally — **only the other device seeing it proves
the server relayed it.**

For same-account multi-device validation, create the room on a phone and join its code on
a tablet. The phone stays host, the tablet starts as a viewer, and reconnecting either
device must leave the other device connected with its existing role. This behavior needs
the updated server; it does not require sharing device IDs or private room credentials.

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

The build itself is the easy half; the gate worth having is the one that proves the new
binary is the one now serving. `/watch/version` reports the build's git SHA, so a workflow
can assert it after the restart instead of trusting that the upload landed.

## Backups

`yfuse-backup.timer` runs `deploy/yfuse-backup.sh` nightly. For each database it takes an online
`.backup`, checks `PRAGMA integrity_check`, compresses it with gzip, encrypts it with
[age](https://age-encryption.org) when recipients are configured, copies it off-host with rsync
only if it is encrypted, and prunes the local set by count per database and by total size.

### One-time setup

The job runs as its own `yfuse-backup` user. It reads the databases through the `yfuse` group,
and it alone owns the backup directory and the off-host SSH key: a compromised service cannot
read that key or rewrite past backups, and the backup job cannot write the live databases.

```bash
sudo apt-get install -y sqlite3 age rsync
sudo useradd --system --home-dir /var/lib/yfuse-backup --create-home \
  --shell /usr/sbin/nologin --groups yfuse yfuse-backup
sudo install -d -o yfuse-backup -g yfuse-backup -m 0700 /var/backups/yfuse
# The service unit now uses StateDirectoryMode=0750 and UMask=0027; fix up existing files once.
sudo chmod 0750 /var/lib/yfuse
sudo find /var/lib/yfuse -maxdepth 1 -name '*.db*' -exec chmod g+r {} +

sudo install -d -o yfuse -g yfuse -m 0755 /opt/yfuse-watch/deploy
sudo install -m 0755 watchTogetherServer/deploy/yfuse-backup.sh /opt/yfuse-watch/deploy/
sudo install -m 0644 watchTogetherServer/deploy/yfuse-backup.service /etc/systemd/system/
sudo install -m 0644 watchTogetherServer/deploy/yfuse-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now yfuse-backup.timer
sudo systemctl start yfuse-backup.service && sudo ls -l /var/backups/yfuse
```

Encryption: generate the age identity **off the server** (a workstation or password manager) and
put only its public recipient on the box. The private key never touches the server; losing it
loses every encrypted backup, so keep two copies.

```bash
age-keygen -o yfuse-backup-identity.txt          # on the workstation; prints the recipient
echo 'age1…' | sudo tee /etc/yfuse-watch/backup.age-recipients
```

`/etc/yfuse-watch/backup.env`:

```bash
YFUSE_BACKUP_AGE_RECIPIENTS=/etc/yfuse-watch/backup.age-recipients
YFUSE_BACKUP_REMOTE=backup@offsite.example:/srv/yfuse-backups   # refused unless encrypted
YFUSE_BACKUP_KEEP_COUNT=14       # snapshots kept per database
YFUSE_BACKUP_MAX_TOTAL_MB=2048   # cap on /var/backups/yfuse; tonight's set is always kept
```

The off-host SSH key lives in `/var/lib/yfuse-backup/.ssh` (mode 0600, owned by
`yfuse-backup`); restrict it on the remote side to rsync into one directory. The job refuses to
copy anything off-host while no recipient is configured: a plaintext copy never leaves the box.

The job needs the service to be running: with WAL, a read-only reader relies on the `-shm` file
the service keeps. While the service is stopped, take snapshots as `yfuse` instead (step 4).

### Restore drill

Do this at least once a quarter, and after any change to the backup setup, on a machine other
than the production server. A backup nobody has restored is a hope, not a backup.

```bash
# 1. Fetch the newest set from the off-host copy and decrypt it with the off-server identity.
for f in account calendar migration-relay qoe watch-state; do
  newest="$(ls -1 "$f"-*.db.gz.age 2>/dev/null | sort | tail -n 1)" || true
  [ -n "$newest" ] || continue
  age --decrypt -i yfuse-backup-identity.txt "$newest" | gunzip > "drill/$f.db"
  sqlite3 "drill/$f.db" 'PRAGMA integrity_check;'      # must print: ok
done
# 2. Start the same build against the copies on a spare port and check it is healthy.
ACCOUNT_DB_PATH=drill/account.db CALENDAR_DB_PATH=drill/calendar.db \
MIGRATION_RELAY_DB_PATH=drill/migration-relay.db QOE_DB_PATH=drill/qoe.db \
WATCH_STATE_DB_PATH=drill/watch-state.db UPDATE_ROOT=drill/update PORT=18080 \
  watchTogetherServer/build/install/watchTogetherServer/bin/watchTogetherServer &
curl --fail http://127.0.0.1:18080/health
# 3. Sign in with a known test account against the drill instance, then stop it.
```

Record the date, the snapshot stamp and the outcome. For a real restore, stop
`yfuse-update.service`, move the damaged database aside (keep it), copy the restored file into
`/var/lib/yfuse` with `install -o yfuse -g yfuse -m 0640`, remove any stale `-wal`/`-shm` next to
it, and start the service again.
