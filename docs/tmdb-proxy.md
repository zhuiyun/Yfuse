# TMDB proxy

Every APK built with `tmdb.token` carries a TMDB read token in `BuildConfig.TMDB_TOKEN`, and
anything in an APK can be extracted. The account server (`watchTogetherServer`) already holds a
TMDB token for calendar ingestion, so a signed-in account reads TMDB through it and the app needs
its own token only for signed-out use. Removing that last use is a separate decision, described
at the end.

## Server

`GET /api/v1/tmdb/{path...}`, installed with the account routes (`TmdbProxyRoutes.kt`).

- **Authentication** is exactly that of the other account routes: HTTPS (or loopback with
  `X-Forwarded-Proto: https`), the per-IP limit, then `Authorization: Bearer <account access
  token>`. Anything else is `401`.
- **Allowlist** (`TmdbProxyAllowlist.kt`): only the reads the app makes, each with its own
  parameters and value patterns. Any other path, parameter, repeated parameter or value is
  refused with `403 tmdb_request_not_allowed`; nothing is trimmed and forwarded.

  | Kind | Reads | Cached for |
  | --- | --- | --- |
  | Charts | `movie/popular`, `movie/now_playing`, `tv/popular`, `tv/airing_today`, `discover/movie`, `discover/tv` | 10 min |
  | Search | `search/movie`, `search/tv`, `search/multi`, `search/person` | 30 min |
  | Records | `movie/{id}`, `tv/{id}`, their `credits`, `external_ids`, `tv/{id}/aggregate_credits`, `tv/{id}/season/{n}`, `person/{id}`, `person/{id}/combined_credits`, `person/{id}/external_ids`, `find/{imdb or tvdb id}` | 2 h |

  `append_to_response` accepts `credits` and `external_ids` (plus `aggregate_credits` on a
  show, `combined_credits` on a person); `include_adult` accepts only `false`.
- **Upstream**: `https://api.themoviedb.org/3` plus the canonical path and query, with the
  server's own bearer. No redirects, 5 s to connect and 12 s in all, at most 4 MiB of body, and
  at most 8 reads in flight; a request that waits 8 s for one gets `503 tmdb_busy`.
- **Cache**: successful JSON answers only, in memory, least recently used out first, at most
  2,048 entries and 24 MiB (counted as UTF-16), 1 MiB per entry. Answers carry
  `X-Yfuse-Tmdb-Cache: hit` or `miss`. A restart empties it.
- **Limits**: 600 requests a minute per IP before authentication, and 240 a minute per account
  across all its devices after it, answered with `429 tmdb_rate_limited` and `Retry-After`. The
  app cannot keep these answers in its own HTTP cache (Caddy and the account handler mark every
  `/api/*` response `no-store`), so the budget is sized for uncached traffic: a cold start with
  the calendar is about 80 reads.
- **TMDB's answers**: `400`, `404` and `422` are passed on and not cached. A `429` is passed on
  with its `Retry-After` (1–120 s, 10 s when absent), and until it passes every cache miss is
  answered `429` without asking TMDB; cache hits are still served. `401` and `403` (TMDB refusing
  the server's token), `5xx`, non-JSON, oversized bodies and transport failures become
  `502 tmdb_unavailable`. A refused server token is never reported as `401`, which the app would
  read as its own session failing.
- **No token**: `503 tmdb_unconfigured`, logged once as `tmdb_proxy_unconfigured`.
- **Logs** (`tmdb_proxy_upstream_failed`, `tmdb_proxy_upstream_limited`) carry the endpoint label
  (`person/{id}`, never the id), the status or exception type, and never the token, the query or
  the body. The request log records the path without the query, as for every route.

## Server environment

Nothing new. The proxy reads the same `TMDB_TOKEN` (a TMDB v4 read access token) that calendar
ingestion uses. If it is not in `/etc/yfuse-watch/environment` yet, add it there (root-only, see
`watch-server-deploy.md`) and restart `yfuse-update`. It should be a token that has never been
built into an APK; see the last section.

After deploying, from any machine:

```bash
# 401 means the route exists; 404 means the running build predates it.
curl -s -o /dev/null -w '%{http_code}\n' https://47.112.219.60/api/v1/tmdb/movie/603
```

Signed in on a phone, open a TMDB page and export the diagnostic log: `tmdb/route_changed` with
`route=proxy` and no `tmdb/proxy_cooldown_started` means reads go through the proxy. On the
server, `journalctl -u yfuse-update` shows `/api/v1/tmdb/...` requests with status 200.

## App

`createTmdbClient` (`core/network/Tmdb.kt`, `TmdbRouting.kt`) routes every request callers still
address to `TMDB_BASE`:

- **Signed in** (`AccountAccessTokenSource.sessionAvailable`): the request is sent to
  `ACCOUNT_BASE_URL/api/v1/tmdb/...` with the account's access token. A `401` renews the token
  once for all requests refused together, through the account's usual refresh. The built-in
  token never goes to the account server, the account token never goes to TMDB, and other hosts
  (images) get neither.
- **Signed out, token built in**: straight to TMDB with it, as before.
- **Neither**: the request fails with `TmdbUnavailableException`, an `IOException`, so TMDB
  features show the same states as when TMDB is unreachable (home error card or cached shelves,
  an empty calendar, a detail page without TMDB data). `tmdb/route_unavailable` is logged once
  per change of route. For ten seconds after launch a build without a token first waits for the
  account's background session restore instead of failing the first home refresh.
- **Proxy cannot serve, token built in**: a `404` without `X-Yfuse-Tmdb-Cache` (a server without
  the route) or a `503` sends reads direct for 2 minutes; a `429` for its `Retry-After`; a `403`
  (allowlist), `502` or a session still refused after renewal retries only that request direct.
  An unreachable account server is not retried. Logged as `tmdb/proxy_cooldown_started` and, at
  most once a minute, `tmdb/proxy_fallback` with the status.

## Rollout order

1. Deploy the server with `TMDB_TOKEN` set, and check the `401` above.
2. Publish the app. Installed older versions keep using their own token and never call the
   proxy.

The app survives the reverse order only while it still carries a token. The same holds for new
reads: a new TMDB call in the app needs its path and parameters in `TmdbProxyAllowlist.kt`,
deployed first. Until then builds with a token fall back to it (`tmdb/proxy_fallback`,
`status=403`); a build without one loses that feature.

## Closing the extraction path (owner's decision)

Nothing here removes the token from release builds. That takes two steps, both the owner's call:

1. Build releases without `tmdb.token`: the release workflow currently requires the `TMDB_TOKEN`
   secret and writes it to `local.properties` (`.github/workflows/publish-android.yml`). Signed-in
   users are unaffected; **signed-out users lose TMDB features** (recommendations, the calendar,
   TMDB pages and 其他作品) and see the existing empty and error states.
2. Revoke the token that shipped in earlier APKs, since those stay extractable forever, and give
   the server a new one. Older installed versions then lose direct TMDB access.
