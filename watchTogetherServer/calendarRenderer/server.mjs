import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import { chromium } from "playwright";

const bindHost = process.env.YFUSE_RENDERER_HOST || "127.0.0.1";
const port = Number.parseInt(process.env.YFUSE_RENDERER_PORT || "8091", 10);
const bearerToken = process.env.YFUSE_CALENDAR_RENDERER_TOKEN || process.env.YFUSE_RENDERER_TOKEN || "";
const storageStatePath = process.env.YFUSE_RENDERER_STORAGE_STATE || "";
const weiboCookieHeader = process.env.YFUSE_RENDERER_WEIBO_COOKIE || "";
const browserChannel = process.env.YFUSE_RENDERER_BROWSER_CHANNEL || "";
const headless = process.env.YFUSE_RENDERER_HEADLESS !== "false";
const debug = process.env.YFUSE_RENDERER_DEBUG === "true";
const maxBodyBytes = 64 * 1024;
const maxHtmlBytes = 4_000_000;
const renderTimeoutMs = 30_000;
const cacheTtlMs = 10 * 60 * 1000;
const maxConcurrentPages = 2;

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error("YFUSE_RENDERER_PORT must be a valid TCP port");
}
if (bearerToken.length < 24) {
  throw new Error("YFUSE_CALENDAR_RENDERER_TOKEN must contain at least 24 characters");
}

const allowedHostSuffixes = [
  "weibo.com",
  "iqiyi.com",
  "youku.com",
  "v.qq.com",
  "mgtv.com"
];
const cache = new Map();
const waiters = [];
let activePages = 0;

function isAllowedUrl(value) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) return false;
    const host = parsed.hostname.toLowerCase();
    return allowedHostSuffixes.some((suffix) => host === suffix || host.endsWith(`.${suffix}`));
  } catch {
    return false;
  }
}

function isPrivateNetworkHost(hostname) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "");
  if (host === "localhost" || host === "::1" || host === "0.0.0.0" || host.endsWith(".local")) return true;
  if (/^(?:127|10)\./.test(host) || /^169\.254\./.test(host) || /^192\.168\./.test(host)) return true;
  const private172 = host.match(/^172\.(\d{1,3})\./);
  return private172 ? Number(private172[1]) >= 16 && Number(private172[1]) <= 31 : false;
}

function isSafeSubresourceUrl(value) {
  try {
    const parsed = new URL(value);
    return ["http:", "https:"].includes(parsed.protocol) && !isPrivateNetworkHost(parsed.hostname);
  } catch {
    return false;
  }
}

function authorized(header) {
  const supplied = typeof header === "string" && header.startsWith("Bearer ") ? header.slice(7) : "";
  const expectedBytes = Buffer.from(bearerToken);
  const suppliedBytes = Buffer.from(supplied);
  return expectedBytes.length === suppliedBytes.length && crypto.timingSafeEqual(expectedBytes, suppliedBytes);
}

function jsonResponse(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(body);
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > maxBodyBytes) throw new Error("request_too_large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

async function acquirePageSlot() {
  if (activePages < maxConcurrentPages) {
    activePages += 1;
    return;
  }
  await new Promise((resolve) => waiters.push(resolve));
  activePages += 1;
}

function releasePageSlot() {
  activePages -= 1;
  waiters.shift()?.();
}

function parseWeiboCookies() {
  if (!weiboCookieHeader) return [];
  return weiboCookieHeader.split(";").map((part) => part.trim()).map((part) => {
    const separator = part.indexOf("=");
    if (separator <= 0) return null;
    return {
      name: part.slice(0, separator).trim(),
      value: part.slice(separator + 1).trim(),
      domain: ".weibo.com",
      path: "/",
      secure: true,
      sameSite: "Lax"
    };
  }).filter(Boolean);
}

function normalizeWeiboTimelinePost(post) {
  if (!post || typeof post !== "object") return null;
  const postId = post.mblogid || post.bid;
  const text = post.text_raw || post.text;
  if (!postId || typeof text !== "string") return null;
  const rawPictures = Array.isArray(post.pics)
    ? post.pics
    : post.pic_infos && typeof post.pic_infos === "object"
      ? Object.values(post.pic_infos)
      : [];
  const pictures = rawPictures.map((picture) => {
    if (!picture || typeof picture !== "object") return null;
    const imageUrl =
      picture.largest?.url || picture.large?.url || picture.original?.url ||
      picture.large_url || picture.url;
    return typeof imageUrl === "string" ? { large_url: imageUrl } : null;
  }).filter(Boolean);
  return { mblogid: String(postId), text_raw: text, pics: pictures };
}

const launchOptions = {
  headless,
  args: process.platform === "linux" ? ["--disable-dev-shm-usage", "--no-sandbox"] : []
};
if (browserChannel) launchOptions.channel = browserChannel;
const browser = await chromium.launch(launchOptions);

async function renderPage(url) {
  const cached = cache.get(url);
  if (cached && Date.now() - cached.createdAt < cacheTtlMs) return cached.html;

  await acquirePageSlot();
  let context;
  try {
    const contextOptions = {
      locale: "zh-CN",
      timezoneId: "Asia/Shanghai",
      userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/136 Safari/537.36"
    };
    if (storageStatePath && fs.existsSync(storageStatePath) && fs.statSync(storageStatePath).isFile()) {
      contextOptions.storageState = storageStatePath;
    }
    context = await browser.newContext(contextOptions);
    const cookies = parseWeiboCookies();
    if (cookies.length > 0) await context.addCookies(cookies);

    const page = await context.newPage();
    const requestedPath = new URL(url).pathname;
    const captureTimeline = /^\/(?:u\/)?\d+\/?$/.test(requestedPath);
    const timelineResponses = [];
    const timelineCaptures = [];
    page.on("response", (pageResponse) => {
      let responseUrl;
      try {
        responseUrl = new URL(pageResponse.url());
      } catch {
        return;
      }
      if (
        !captureTimeline ||
        responseUrl.hostname !== "weibo.com" ||
        responseUrl.pathname !== "/ajax/statuses/mymblog" ||
        pageResponse.status() < 200 ||
        pageResponse.status() >= 300
      ) return;
      const capture = pageResponse.text().then((body) => {
        if (Buffer.byteLength(body) > 1_000_000) return;
        const parsed = JSON.parse(body);
        const posts = Array.isArray(parsed?.data?.list) ? parsed.data.list : [];
        for (const post of posts) {
          const normalized = normalizeWeiboTimelinePost(post);
          if (normalized) timelineResponses.push(normalized);
        }
      }).catch(() => {});
      timelineCaptures.push(capture);
    });
    if (debug) {
      page.on("requestfailed", (failedRequest) => {
        let host = "invalid-url";
        try { host = new URL(failedRequest.url()).hostname; } catch {}
        process.stderr.write(`renderer request failed: ${failedRequest.resourceType()} ${host} ${failedRequest.failure()?.errorText || "unknown"}\n`);
      });
    }
    await page.route("**/*", async (route) => {
      const requestUrl = route.request().url();
      const resourceType = route.request().resourceType();
      if (!isSafeSubresourceUrl(requestUrl)) {
        if (debug) process.stderr.write(`renderer blocked unsafe subresource: ${resourceType}\n`);
        return route.abort();
      }
      if (route.request().isNavigationRequest() && !isAllowedUrl(requestUrl)) {
        if (debug) {
          let host = "invalid-url";
          try { host = new URL(requestUrl).hostname; } catch {}
          process.stderr.write(`renderer blocked navigation: ${host}\n`);
        }
        return route.abort();
      }
      if (["font", "media"].includes(resourceType)) return route.abort();
      return route.continue();
    });
    await page.goto(url, { waitUntil: "domcontentloaded", timeout: renderTimeoutMs });

    for (let index = 0; index < 12; index += 1) {
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForTimeout(650);
    }
    await page.waitForTimeout(700);
    await Promise.allSettled(timelineCaptures);

    if (!isAllowedUrl(page.url())) throw new Error("redirected_to_disallowed_host");
    const html = await page.content();
    if (Buffer.byteLength(html) > maxHtmlBytes) throw new Error("rendered_page_too_large");
    const hasStorageState = storageStatePath && fs.existsSync(storageStatePath) && fs.statSync(storageStatePath).isFile();
    if (html.includes("Sina Visitor System") && !weiboCookieHeader && !hasStorageState) {
      throw new Error("weibo_session_required");
    }
    const distinctTimelineResponses = [...new Map(
      timelineResponses.map((post) => [post.mblogid, post])
    ).values()];
    const timelineJson = distinctTimelineResponses.length > 0
      ? JSON.stringify({ timelineResponses: distinctTimelineResponses })
      : "";
    const renderedContent = timelineJson && Buffer.byteLength(timelineJson) <= maxHtmlBytes
      ? timelineJson
      : html;
    cache.set(url, { createdAt: Date.now(), html: renderedContent });
    if (cache.size > 100) {
      const oldest = [...cache.entries()].sort((left, right) => left[1].createdAt - right[1].createdAt)[0];
      if (oldest) cache.delete(oldest[0]);
    }
    return renderedContent;
  } finally {
    await context?.close();
    releasePageSlot();
  }
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    return jsonResponse(response, 200, { status: "ok", activePages, cachedPages: cache.size });
  }
  if (request.method !== "POST" || request.url !== "/v1/render") {
    return jsonResponse(response, 404, { error: "not_found" });
  }
  if (!authorized(request.headers.authorization)) {
    return jsonResponse(response, 401, { error: "unauthorized" });
  }
  try {
    const payload = await readJson(request);
    if (!isAllowedUrl(payload?.url)) return jsonResponse(response, 400, { error: "unsupported_url" });
    const html = await renderPage(payload.url);
    return jsonResponse(response, 200, { html });
  } catch (error) {
    const code = error instanceof Error ? error.message : "render_failed";
    const status = code === "request_too_large" ? 413 : code === "weibo_session_required" ? 503 : 502;
    return jsonResponse(response, status, { error: code.slice(0, 120) });
  }
});

server.listen(port, bindHost, () => {
  process.stdout.write(`calendar renderer listening on ${bindHost}:${port}\n`);
});

async function shutdown() {
  server.close();
  await browser.close();
  process.exit(0);
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
