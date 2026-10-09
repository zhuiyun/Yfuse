import assert from "node:assert/strict";
import test from "node:test";
import { isAllowedUrl, isPrivateNetworkHost, isSafeSubresourceUrl } from "./network-policy.mjs";

test("navigation keeps the exact HTTPS host boundary", () => {
  for (const url of ["https://weibo.com/u/123", "https://www.iqiyi.com/video"]) {
    assert.equal(isAllowedUrl(url), true, url);
  }
  for (const url of ["http://weibo.com/", "https://weibo.com.attacker.invalid/", "https://notweibo.com/", "https://user:secret@weibo.com/"]) {
    assert.equal(isAllowedUrl(url), false, url);
  }
});

test("subresources reject private IPv4, IPv6 and mapped-address variants", () => {
  for (const host of [
    "localhost", "LOCALHOST.", "assets.localhost", "printer.local", "127.1", "2130706433", "0x7f000001",
    "10.1.2.3", "172.16.1.1", "172.31.255.255", "192.168.0.1", "169.254.169.254", "0.1.2.3",
    "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
    "[::]", "[::1]", "[0:0:0:0:0:0:0:1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[ff02::1]",
    "[::ffff:127.0.0.1]", "[::ffff:192.168.0.1]", "[::ffff:a00:1]", "[::127.0.0.1]",
  ]) {
    assert.equal(isSafeSubresourceUrl(`https://${host}/asset`), false, host);
  }
});

test("public CDN names and public addresses stay available", () => {
  for (const host of ["cdn.example.com", "8.8.8.8", "172.15.255.255", "172.32.0.1", "[2606:4700:4700::1111]", "[::ffff:8.8.8.8]"]) {
    assert.equal(isSafeSubresourceUrl(`https://${host}/asset`), true, host);
  }
  for (const url of ["file:///etc/passwd", "data:text/html,hello", "https://user:secret@cdn.example.com/", "not a url"]) {
    assert.equal(isSafeSubresourceUrl(url), false, url);
  }
});

test("direct host checks normalize dotted IPv4-mapped IPv6 addresses", () => {
  for (const host of ["::ffff:10.0.0.1", "[::ffff:192.168.1.1]", "0:0:0:0:0:ffff:127.0.0.1", "::127.0.0.1"]) {
    assert.equal(isPrivateNetworkHost(host), true, host);
  }
  assert.equal(isPrivateNetworkHost("::ffff:8.8.8.8"), false);
});
