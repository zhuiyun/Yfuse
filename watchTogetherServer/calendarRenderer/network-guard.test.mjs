import assert from "node:assert/strict";
import http from "node:http";
import net from "node:net";
import test from "node:test";
import { isBlockedAddress, isSafeRequestUrl, resolvePublicAddress, startEgressProxy } from "./network-guard.mjs";

const fakeLookup = (table) => (hostname, _options, callback) => {
  const addresses = table[hostname];
  if (!addresses) return callback(Object.assign(new Error("ENOTFOUND"), { code: "ENOTFOUND" }));
  callback(null, addresses.map((address) => ({ address, family: net.isIPv6(address) ? 6 : 4 })));
};

test("private, local and metadata addresses are blocked", () => {
  for (const address of [
    "127.0.0.1", "127.255.255.254", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255",
    "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.100.100.200", "100.127.255.255",
    "224.0.0.1", "255.255.255.255", "::1", "::", "fc00::1", "fdff::1", "fe80::1", "febf::1",
    "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:7f00:1", "64:ff9b::a9fe:a9fe", "ff02::1",
    "not-an-ip"
  ]) {
    assert.equal(isBlockedAddress(address), true, address);
  }
  for (const address of ["8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8"]) {
    assert.equal(isBlockedAddress(address), false, address);
  }
});

test("a name is trusted only when every address it resolves to is public", async () => {
  const lookup = fakeLookup({
    "cdn.example": ["93.184.216.34"],
    "mixed.example": ["93.184.216.34", "10.0.0.5"],
    "rebind.example": ["127.0.0.1"],
    "v6.example": ["2606:2800:220:1::1", "fe80::1"]
  });
  assert.equal(await resolvePublicAddress("cdn.example", { lookup }), "93.184.216.34");
  assert.equal(await resolvePublicAddress("mixed.example", { lookup }), null);
  assert.equal(await resolvePublicAddress("rebind.example", { lookup }), null);
  assert.equal(await resolvePublicAddress("v6.example", { lookup }), null);
  assert.equal(await resolvePublicAddress("missing.example", { lookup }), null);
  assert.equal(await resolvePublicAddress("[::1]", { lookup }), null);
  assert.equal(await isSafeRequestUrl("https://cdn.example/a.js", { lookup }), true);
  assert.equal(await isSafeRequestUrl("https://user:pw@cdn.example/", { lookup }), false);
  assert.equal(await isSafeRequestUrl("file:///etc/passwd", { lookup }), false);
  assert.equal(await isSafeRequestUrl("http://100.100.100.200/latest/meta-data/", { lookup }), false);
});

function connectThrough(proxyPort, authority) {
  return new Promise((resolve, reject) => {
    const request = http.request({ host: "127.0.0.1", port: proxyPort, method: "CONNECT", path: authority });
    request.on("connect", (response, socket) => {
      socket.destroy();
      resolve(response.statusCode);
    });
    request.on("error", reject);
    request.end();
  });
}

test("the proxy refuses CONNECT and plain requests to names that resolve privately", async () => {
  const proxy = await startEgressProxy({ lookup: fakeLookup({ "internal.example": ["127.0.0.1"] }) });
  const port = proxy.address().port;
  try {
    assert.equal(await connectThrough(port, "internal.example:443"), 403);
    assert.equal(await connectThrough(port, "169.254.169.254:443"), 403);
    const status = await new Promise((resolve, reject) => {
      const request = http.request(
        { host: "127.0.0.1", port, method: "GET", path: "http://internal.example/secret" },
        (response) => resolve(response.statusCode)
      );
      request.on("error", reject);
      request.end();
    });
    assert.equal(status, 403);
  } finally {
    proxy.close();
  }
});

test("the proxy connects to the address it checked, and only on web ports", async () => {
  const target = net.createServer((socket) => socket.end());
  await new Promise((resolve) => target.listen(0, "127.0.0.1", resolve));
  const targetPort = target.address().port;
  // Loopback stands in for a public host here; the address check itself is covered above.
  const proxy = await startEgressProxy({
    lookup: fakeLookup({ "public.example": ["127.0.0.1"] }),
    isBlocked: () => false,
    allowedPorts: [targetPort]
  });
  const port = proxy.address().port;
  try {
    assert.equal(await connectThrough(port, `public.example:${targetPort}`), 200);
    assert.equal(await connectThrough(port, "public.example:22"), 403);
  } finally {
    proxy.close();
    target.close();
  }
});
