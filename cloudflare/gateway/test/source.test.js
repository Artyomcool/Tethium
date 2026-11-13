import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

test("worker exposes only health and authenticated websocket rooms", async () => {
  const source = await readFile(new URL("../src/index.js", import.meta.url), "utf8");
  assert.match(source, /GATEWAY_ADMISSION/);
  assert.match(source, /x-tethium-ticket/);
  assert.match(source, /x-tethium-auth/);
  assert.match(source, /role !== "host"/);
  assert.match(source, /acceptWebSocket/);
  assert.doesNotMatch(source, /console\.log|request\.text\(|request\.arrayBuffer\(/);
  assert.doesNotMatch(source, /x-tethium-gateway/);
});
