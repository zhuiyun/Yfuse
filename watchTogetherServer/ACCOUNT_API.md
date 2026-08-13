# Yfuse account and encrypted-sync API

The account API is mounted under `/api/v1` in `watchTogetherServer`. Account persistence is
separate from the in-memory watch-room state and uses the SQLite file selected by
`ACCOUNT_DB_PATH` (default: `/var/lib/yfuse/account.db`). The service account must be able to
create that file, and the database directory must not be web-accessible.

The production entry point binds to `127.0.0.1` by default. Set `HOST` explicitly only when a
different listener is intended. The normal deployment is a local Caddy-to-Ktor connection;
do not expose the default account listener directly to the internet.

## Server controls

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `ACCOUNT_DB_PATH` | `/var/lib/yfuse/account.db` | SQLite database file |
| `ACCOUNT_REGISTRATION_ENABLED` | `false` | Whether new registrations are accepted |
| `ACCOUNT_REGISTRATION_INVITE_CODES` | empty | Comma-separated one-time URL-safe invitation codes |
| `ACCOUNT_MAX_USERS` | `1000` | Maximum registered users (valid range `1..100000`) |
| `HOST` | `127.0.0.1` | Ktor bind host |
| `PORT` | `8080` | Ktor bind port |

When registration is disabled or the user cap has been reached, registration returns `503`
with `registration_closed`. The check happens before password hashing and does not reveal
which condition closed registration. For a private deployment, prefer high-entropy one-time
codes in `ACCOUNT_REGISTRATION_INVITE_CODES` while public registration stays disabled. Only
SHA-256 code digests are recorded after redemption; each code creates at most one account.

Blocking SQLite calls and CPU-heavy password hashing run on a dedicated four-thread account
executor, not Ktor CIO event threads. At most four account operations run concurrently by
default; overload is rejected with `503 account_busy` and `Retry-After: 1`.

## Transport boundary

Every account endpoint requires HTTPS. Direct HTTPS requests are accepted. A plain HTTP
request is accepted as already secured only when the socket peer is loopback and the request
has the exact header `X-Forwarded-Proto: https`; this is the expected local reverse-proxy path.
A public peer cannot opt into trust by forging the header. Rejected requests receive `426`
with error code `https_required`.

For rate limiting, the socket peer is authoritative. Only a loopback peer may supply
`X-Forwarded-For`, and that header must contain exactly one syntactically valid IPv4 or IPv6
literal. Duplicate headers, comma-separated chains, hostnames, and malformed values are
rejected with `400 forwarded_for_invalid`. A non-loopback peer's header is ignored and its
socket address is used.

Requests and responses use JSON (`application/json`). The body limit is derived from the
Base64URL expansion of the 256 KiB decoded ciphertext limit plus 32 KiB reserved for the JSON
envelope and wrapper metadata (currently 382,294 bytes); changing the ciphertext limit updates
the request and response limits with it. `Authorization` is limited to 256 characters. Every
account response, including `204` and errors, includes `Cache-Control: no-store` and
`Pragma: no-cache`. A server-produced response that nevertheless exceeds the derived bound is
reported as `500 response_too_large` instead of a generic internal error.

## Authentication and profile

| Method and path | Request | Response |
| --- | --- | --- |
| `POST /api/v1/auth/register` | `{username,password,nickname?,avatarId?,inviteCode?,deviceName?}` | `201 AuthResponse` |
| `POST /api/v1/auth/login` | `{username,password,deviceName?}` | `200 AuthResponse` |
| `POST /api/v1/auth/refresh` | `{refreshToken,deviceName?}` | `200 AuthResponse` with rotated tokens |
| `POST /api/v1/auth/logout` | Bearer access token | `204` |
| `GET /api/v1/account/profile` | Bearer access token | `200 UserResponse` |
| `PUT /api/v1/account/profile` | Bearer plus `{nickname?,avatarId?}` | `200 UserResponse` |
| `PUT /api/v1/account/password` | Bearer plus the password-change body below | `200 AuthResponse` |
| `GET /api/v1/account/sessions` | Bearer | Active device sessions, including current marker |
| `DELETE /api/v1/account/sessions/{id}` | Bearer | Revoke one owned session |
| `POST /api/v1/account/sessions/revoke-others` | Bearer | Revoke all except current |
| `POST /api/v1/account/sessions/revoke-all` | Bearer | Revoke every session |
| `GET /api/v1/account/export` | Bearer | Profile and opaque encrypted sync envelope |
| `DELETE /api/v1/account` | Bearer plus `{password}` | Permanently delete account |

`AuthResponse` is:

```json
{
  "user": {
    "id": "uuid",
    "username": "Alice",
    "nickname": "小鱼",
    "avatarId": 2,
    "createdAtEpochMs": 0,
    "updatedAtEpochMs": 0
  },
  "accessToken": "opaque-base64url",
  "accessExpiresAtEpochMs": 0,
  "refreshToken": "opaque-base64url",
  "refreshExpiresAtEpochMs": 0
}
```

Access tokens live for 15 minutes and refresh tokens for 30 days. Refresh atomically rotates
both values, immediately invalidating the old pair. Logout revokes that session, including its
refresh token. At most ten recent active sessions are retained per user. SQLite stores only
SHA-256 token digests, never bearer or refresh-token plaintext.

Passwords use JCA `PBKDF2WithHmacSHA256`, 600,000 iterations, a random 16-byte salt, and a
32-byte output. Invalid login responses do not distinguish an unknown username from a wrong
password. Failed logins are additionally limited across IPs by a SHA-256 digest of the
normalized username (10 failures per five minutes), applying the same response behavior to
existing and unknown usernames.

### Password change

The client must first unwrap the vault key using the current login password, derive a new key
from the new login password, and rewrap that same vault key. It then sends:

```json
{
  "currentPassword": "current login password",
  "newPassword": "new login password",
  "expectedSyncVersion": 3,
  "keyVersion": 1,
  "wrapVersion": 1,
  "wrapKdf": "PBKDF2-HMAC-SHA256",
  "wrapIterations": 600000,
  "wrappedVaultKey": "base64url-48-bytes",
  "wrapSalt": "base64url-16-bytes",
  "wrapNonce": "base64url-12-bytes"
}
```

For an account with sync data, `expectedSyncVersion` and `keyVersion` must match the stored
record. A stale version returns `409 sync_version_conflict`; a key-version mismatch returns
`409 sync_key_version_conflict`. An incorrect current password returns `403`
`current_password_invalid` (intentionally not `401`, so clients do not attempt token refresh).

On success, one SQLite transaction updates the login-password digest, replaces only the sync
wrapper fields, revokes every old session, and creates exactly one replacement session. The
response is the new `AuthResponse`; all old access and refresh tokens are invalid immediately.
An in-flight sync `PUT` or `DELETE` that authenticated with an old access token before the
password-change transaction committed rechecks that exact session inside its own write
transaction. It returns `401 unauthorized` without changing the payload, wrapper, nonce
history, or sync revision. This commit-time check also applies after logout, refresh rotation,
or access-token expiry.
The encrypted payload, payload nonce, sync version, and sync update timestamp are unchanged.
When no sync payload exists, `expectedSyncVersion` must still match the current revision: it is
`0` only for an account that has never written or deleted sync data, and is non-zero for a
deletion tombstone. The full wrapper-shaped request is still required by the API, but without a
payload there is no wrapper to replace; the transaction changes only password/session state and
leaves the revision unchanged.

## Opaque encrypted sync

| Method and path | Request | Response |
| --- | --- | --- |
| `GET /api/v1/account/sync` | Bearer access token | `200 SyncResponse` |
| `PUT /api/v1/account/sync` | Bearer plus `PutSyncRequest` | `200 SyncResponse` |
| `DELETE /api/v1/account/sync` | Bearer access token | `200 SyncResponse` tombstone |

`GET` returns `{"version":0}` only before the first write or delete. After deletion it returns a
tombstone such as `{"version":4,"updatedAtEpochMs":...}` with no `payload`. `PUT` performs an
optimistic write against this monotonic revision:

```json
{
  "baseVersion": 3,
  "payload": {
    "schemaVersion": 1,
    "algorithm": "AES-256-GCM",
    "keyVersion": 1,
    "nonce": "base64url-12-bytes",
    "ciphertext": "base64url-ciphertext-plus-16-byte-tag",
    "wrapVersion": 1,
    "wrapKdf": "PBKDF2-HMAC-SHA256",
    "wrapIterations": 600000,
    "wrappedVaultKey": "base64url-48-bytes",
    "wrapSalt": "base64url-16-bytes",
    "wrapNonce": "base64url-12-bytes"
  }
}
```

The only accepted sync schema is `schemaVersion=1`; the encryption algorithm is
`AES-256-GCM`. `baseVersion` must equal the latest payload or tombstone revision. Success stores
`baseVersion + 1` and returns the envelope plus `updatedAtEpochMs`. A stale `baseVersion`
receives `409 sync_version_conflict` with `currentVersion`. Ciphertext is capped at 256 KiB
decoded. All binary values use canonical Base64URL without padding.

All six wrapper fields are an all-or-none group. An upload made when no payload exists (the
initial state or a tombstone) must include a complete wrapper. A later upload using the same
`keyVersion` may omit all six, in which case the server inherits the stored wrapper. Changing
`keyVersion` requires a complete new wrapper. The server accepts only `wrapVersion=1`,
`wrapKdf="PBKDF2-HMAC-SHA256"`, and `wrapIterations` between 100,000 and 2,000,000 inclusive.
The wrapped value is a 32-byte vault key encrypted with AES-GCM, including its 16-byte tag.

Clients bind ciphertext to its owner and version with these UTF-8 AAD values:

```text
yfuse-sync:v1:{userId}:{nextVersion}:{keyVersion}
yfuse-vault-key:v1:{userId}:{keyVersion}:{wrapVersion}:{wrapKdf}:{wrapIterations}
```

The second line is the caller-provided wrapper AAD. `VaultCrypto` additionally authenticates
its recovery prefix (`yfuse-recovery-key-v1`), envelope version, PBKDF2 iteration count, and
salt around that caller AAD. Changing any of those values therefore makes unwrap fail rather
than silently changing key-derivation parameters.

The payload nonce must never repeat for the same user and key version. The server rejects
known reuse with `409 sync_nonce_reused`. Nonce history is bounded to 4,096 recent entries per
user and is age-cleaned after 180 days, so the client remains responsible for nonce uniqueness.

The client generates a random 256-bit vault key and wraps it with a key derived from the login
password. This lets a password change replace the wrapper without re-encrypting the full sync
payload. It is not an independent recovery password and does not provide automatic recovery:
the client must know the current login password and possess/decrypt the existing wrapper before
changing it. The server receives login passwords over TLS for authentication and stores the
opaque wrapper and encrypted payload. This design is intended only to reduce disclosure from a
stolen database file or backup; it is not a claim of online zero knowledge.

`DELETE /api/v1/account/sync` atomically removes the payload and that user's nonce history, but
retains a persistent tombstone and advances its revision once per accepted request. It returns
that tombstone as `200 SyncResponse`, preserves the user/profile/sessions, and never resets the
revision to zero. Consequently a stale `PUT` or password-change CAS prepared before deletion
cannot match a newly created payload after deletion (the ABA case). A client that loses the
delete response can recover the current revision with `GET`.

## Rate limits and errors

Default fixed-window limits apply both per trusted client IP at the HTTP boundary and per
authenticated user inside the account service where applicable:

| Operation | Limit |
| --- | --- |
| Register and login combined, per IP | 10 requests/minute |
| Refresh, per IP | 30 requests/minute |
| Logout, per IP | 30 requests/minute |
| Profile `GET`, per IP | 120 requests/minute |
| Profile `PUT`, per IP | 30 requests/minute |
| Sync `GET`, per IP and per user | 120 requests/minute |
| Sync `PUT` and `DELETE` combined, per IP and per user | 30 requests/minute |
| Password change, per IP and per user | 5 requests/15 minutes |
| Failed login, per normalized username across IPs | 10 failures/5 minutes |

The in-memory limiter tables are capped at 10,000 entries and use expiry queues for bounded
cleanup. When full, they reject new identities instead of evicting active buckets. Limited
requests receive `429 rate_limited` and a seconds-valued `Retry-After`; limits reset when the
server process restarts.

Errors use one stable envelope and never include passwords, tokens, SQL details, or parser
messages:

```json
{"error":{"code":"invalid_credentials","message":"用户名或密码错误"}}
```

Version and key-version conflicts additionally include `currentVersion`.
