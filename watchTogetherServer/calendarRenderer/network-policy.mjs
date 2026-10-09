import { isIP } from "node:net";

const allowedHostSuffixes = ["weibo.com", "iqiyi.com", "youku.com", "v.qq.com", "mgtv.com"];

export function isAllowedUrl(value) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) return false;
    const host = parsed.hostname.toLowerCase();
    return allowedHostSuffixes.some((suffix) => host === suffix || host.endsWith(`.${suffix}`));
  } catch {
    return false;
  }
}

function isPrivateIpv4(host) {
  const [a, b] = host.split(".").map(Number);
  return a === 0 || a === 10 || a === 127 || a >= 224 ||
    (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
    (a === 198 && (b === 18 || b === 19));
}

export function isPrivateNetworkHost(hostname) {
  let host = hostname.toLowerCase().replace(/^\[|\]$/g, "").replace(/\.$/, "");
  if (host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true;
  if (isIP(host) === 4) return isPrivateIpv4(host);
  if (isIP(host) !== 6) return false;

  // URL normalizes dotted mapped addresses, e.g. ::ffff:127.0.0.1, into hex words.
  host = new URL(`http://[${host}]/`).hostname.slice(1, -1);
  const halves = host.split("::");
  const left = halves[0] ? halves[0].split(":") : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  const words = halves.length === 1 ? left : [...left, ...Array(8 - left.length - right.length).fill("0"), ...right];
  const values = words.map((word) => Number.parseInt(word, 16));
  if (values.slice(0, 5).every((word) => word === 0) && values[5] === 0xffff) {
    return isPrivateIpv4([values[6] >> 8, values[6] & 255, values[7] >> 8, values[7] & 255].join("."));
  }
  // Only global unicast IPv6 is eligible. Loopback, unspecified, ULA, link-local and multicast
  // must not become a bypass for the IPv4 private-network guard.
  return (values[0] & 0xe000) !== 0x2000;
}

export function isSafeSubresourceUrl(value) {
  try {
    const parsed = new URL(value);
    return ["http:", "https:"].includes(parsed.protocol) &&
      !parsed.username && !parsed.password && !isPrivateNetworkHost(parsed.hostname);
  } catch {
    return false;
  }
}
