// Egress guard for the renderer's Chromium: every connection the browser makes goes through a
// local proxy that resolves the destination itself, refuses it if any address is private, and
// then connects to the address it checked. A hostname filter alone missed names that resolve to
// private addresses, and redirects, which Playwright's route handler never sees.
import dns from "node:dns";
import http from "node:http";
import net from "node:net";

const BLOCKED_V4 = [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10], // carrier-grade NAT; includes Aliyun's metadata service, 100.100.100.200
  ["127.0.0.0", 8],
  ["169.254.0.0", 16], // link-local; the usual cloud metadata address
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["224.0.0.0", 4], // multicast
  ["240.0.0.0", 4], // reserved, and the broadcast address
];

function ipv4ToNumber(address) {
  return address.split(".").reduce((value, part) => value * 256 + Number(part), 0);
}

function inV4Range(address, [base, bits]) {
  const size = 2 ** (32 - bits);
  return Math.floor(ipv4ToNumber(address) / size) === Math.floor(ipv4ToNumber(base) / size);
}

/** Expands an IPv6 literal into its eight 16-bit groups, or null when it is not one. */
function ipv6Groups(address) {
  let text = address.toLowerCase().split("%")[0];
  let tail = [];
  const dotted = text.match(/^(.*:)(\d+\.\d+\.\d+\.\d+)$/);
  if (dotted) {
    if (!net.isIPv4(dotted[2])) return null;
    const value = ipv4ToNumber(dotted[2]);
    tail = [Math.floor(value / 65536), value % 65536];
    text = dotted[1].endsWith("::") ? dotted[1] : dotted[1].slice(0, -1);
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const parse = (part) => (part === "" ? [] : part.split(":").map((group) => Number.parseInt(group, 16)));
  const head = parse(halves[0]);
  const rest = halves.length === 2 ? parse(halves[1]) : [];
  const missing = 8 - head.length - rest.length - tail.length;
  if (halves.length === 1 && missing !== 0) return null;
  if (missing < 0) return null;
  const groups = [...head, ...Array(missing).fill(0), ...rest, ...tail];
  return groups.length === 8 && groups.every((group) => Number.isInteger(group) && group >= 0 && group <= 0xffff)
    ? groups
    : null;
}

/** Whether the browser must not reach [address], an IPv4 or IPv6 literal. Anything else is blocked. */
export function isBlockedAddress(address) {
  if (net.isIPv4(address)) return BLOCKED_V4.some((range) => inV4Range(address, range));
  if (!net.isIPv6(address)) return true;
  const groups = ipv6Groups(address);
  if (!groups) return true;
  const embeddedV4 = (high, low) => `${high >> 8}.${high & 255}.${low >> 8}.${low & 255}`;
  if (groups.every((group) => group === 0)) return true; // ::
  if (groups.slice(0, 7).every((group) => group === 0) && groups[7] === 1) return true; // ::1
  // IPv4-mapped (::ffff:a.b.c.d), IPv4-compatible (::a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) carry an IPv4 address.
  if (groups.slice(0, 5).every((group) => group === 0) && (groups[5] === 0xffff || groups[5] === 0)) {
    return isBlockedAddress(embeddedV4(groups[6], groups[7]));
  }
  if (groups[0] === 0x64 && groups[1] === 0xff9b && groups.slice(2, 6).every((group) => group === 0)) {
    return isBlockedAddress(embeddedV4(groups[6], groups[7]));
  }
  if ((groups[0] & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
  if ((groups[0] & 0xffc0) === 0xfe80) return true; // fe80::/10 link-local
  if ((groups[0] & 0xff00) === 0xff00) return true; // ff00::/8 multicast
  return false;
}

function lookupAll(hostname, lookup) {
  return new Promise((resolve, reject) => {
    lookup(hostname, { all: true, verbatim: true }, (error, addresses) => (error ? reject(error) : resolve(addresses)));
  });
}

/**
 * Resolves [hostname] and returns the address to connect to, or null when the name is unknown or
 * any of its addresses is blocked: a name that also points somewhere private is not trusted with
 * the public one.
 */
export async function resolvePublicAddress(hostname, { lookup = dns.lookup, isBlocked = isBlockedAddress } = {}) {
  const host = String(hostname || "").replace(/^\[|\]$/g, "");
  if (!host) return null;
  if (net.isIP(host)) return isBlocked(host) ? null : host;
  let addresses;
  try {
    addresses = await lookupAll(host, lookup);
  } catch {
    return null;
  }
  if (!Array.isArray(addresses) || addresses.length === 0) return null;
  if (addresses.some((entry) => isBlocked(entry.address))) return null;
  return addresses[0].address;
}

/** Whether a URL the page asks for may be fetched at all, before the proxy checks its address. */
export async function isSafeRequestUrl(value, options) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    return false;
  }
  if (!["http:", "https:"].includes(parsed.protocol) || parsed.username || parsed.password) return false;
  return (await resolvePublicAddress(parsed.hostname, options)) !== null;
}

function splitHostPort(authority, defaultPort) {
  const bracketed = authority.match(/^\[([^\]]+)\](?::(\d+))?$/);
  if (bracketed) return { host: bracketed[1], port: Number(bracketed[2] || defaultPort) };
  const separator = authority.lastIndexOf(":");
  if (separator > 0 && authority.indexOf(":") === separator) {
    return { host: authority.slice(0, separator), port: Number(authority.slice(separator + 1)) };
  }
  return { host: authority, port: defaultPort };
}

/**
 * The proxy Chromium is pointed at. HTTPS goes through CONNECT, so TLS stays end to end with the
 * browser; plain HTTP is forwarded with the checked address and the original Host header. Only
 * ports 80 and 443 are reachable. Listens on loopback and port 0 unless told otherwise.
 */
export function startEgressProxy({ lookup, isBlocked, allowedPorts = [80, 443], onBlocked = () => {} } = {}) {
  const options = { lookup, isBlocked };
  const server = http.createServer(async (request, response) => {
    let target;
    try {
      target = new URL(request.url);
    } catch {
      response.writeHead(400).end();
      return;
    }
    const port = Number(target.port || 80);
    const address =
      target.protocol === "http:" && allowedPorts.includes(port)
        ? await resolvePublicAddress(target.hostname, options)
        : null;
    if (!address) {
      onBlocked(target.hostname);
      response.writeHead(403).end();
      return;
    }
    const headers = { ...request.headers, host: target.host };
    delete headers["proxy-connection"];
    delete headers["proxy-authorization"];
    const upstream = http.request(
      { host: address, port, method: request.method, path: `${target.pathname}${target.search}`, headers },
      (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
        upstreamResponse.pipe(response);
      },
    );
    upstream.on("error", () => response.destroy());
    request.pipe(upstream);
  });
  server.on("connect", async (request, client, head) => {
    const { host, port } = splitHostPort(request.url || "", 443);
    const address = allowedPorts.includes(port) ? await resolvePublicAddress(host, options) : null;
    if (!address) {
      onBlocked(host);
      client.end("HTTP/1.1 403 Forbidden\r\n\r\n");
      return;
    }
    const upstream = net.connect({ host: address, port }, () => {
      client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (head && head.length) upstream.write(head);
      upstream.pipe(client);
      client.pipe(upstream);
    });
    upstream.on("error", () => client.destroy());
    client.on("error", () => upstream.destroy());
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}
