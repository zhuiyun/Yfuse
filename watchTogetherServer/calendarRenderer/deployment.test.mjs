import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const read = (name) => fs.readFileSync(new URL(name, import.meta.url), "utf8");

test("container browsers match the locked Playwright package", () => {
  const dependency = JSON.parse(read("package.json")).dependencies.playwright;
  const lock = JSON.parse(read("package-lock.json"));
  const image = read("Dockerfile").match(/^FROM mcr\.microsoft\.com\/playwright:v(\d+\.\d+\.\d+)-noble$/m);
  assert.ok(image, "renderer must pin its browser image");
  assert.equal(image[1], dependency, "a different image version cannot supply Playwright's expected browser binary");
  assert.equal(lock.packages["node_modules/playwright"].version, dependency);
  assert.equal(lock.packages["node_modules/playwright-core"].version, dependency);
});

test("container includes all relative runtime module dependencies", () => {
  const copied = read("Dockerfile").split("\n")
    .filter((line) => line.startsWith("COPY "))
    .flatMap((line) => line.trim().split(/\s+/).slice(1, -1));
  const visited = new Set();
  const visit = (name) => {
    if (visited.has(name)) return;
    visited.add(name);
    assert.ok(copied.includes(name), `${name} must be copied into the renderer image`);
    for (const match of read(name).matchAll(/from\s+["']\.\/([^"']+)["']/g)) visit(match[1]);
  };
  visit("server.mjs");
});
