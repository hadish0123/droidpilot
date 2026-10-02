import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";

function waitForReady(child, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("remote MCP did not start")), timeoutMs);
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
      if (stderr.includes("remote MCP listening")) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error("remote MCP exited early: " + code + "\n" + stderr));
    });
  });
}

test("remote MCP registers a private device and accepts its signed MCP URL", async (t) => {
  const port = 39000 + Math.floor(Math.random() * 1000);
  const base = "http://127.0.0.1:" + port;
  const child = spawn(process.execPath, ["dist/remote-http.js"], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      PORT: String(port),
      PRIME_PAIRING_SECRET: "test-pairing-secret-that-is-long-enough-for-tests-1234567890",
      PRIME_PUBLIC_BASE_URL: base,
      PRIME_MCP_BEARER: "legacy-test-bearer-that-is-long-enough-1234567890",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => child.kill("SIGTERM"));

  await waitForReady(child);

  const health = await fetch(base + "/health");
  assert.equal(health.status, 200);
  const healthBody = await health.json();
  assert.equal(healthBody.ok, true);

  const pair = await fetch(base + "/phone/pair", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      deviceId: "prime-test-device",
      deviceProof: "device-proof-that-is-at-least-thirty-two-characters",
    }),
  });
  assert.equal(pair.status, 200);
  const paired = await pair.json();
  assert.match(paired.credential, /^prime-test-device_[a-f0-9]{32}\.[a-f0-9]{64}$/);
  assert.ok(paired.mcpUrl.startsWith(base + "/mcp/"));

  const bad = await fetch(base + "/mcp/not-a-valid-token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "ping" }),
  });
  assert.equal(bad.status, 401);

  const initialize = await fetch(paired.mcpUrl, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json, text/event-stream",
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "prime-test", version: "1.0.0" },
      },
    }),
  });
  assert.equal(initialize.status, 200);
  const initialized = await initialize.json();
  assert.equal(initialized.jsonrpc, "2.0");
  assert.equal(initialized.id, 1);
  assert.equal(initialized.result.serverInfo.name, "prime-p6-remote");

  const legacyInitialize = await fetch(base + "/mcp", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json, text/event-stream",
      "authorization": "Bearer legacy-test-bearer-that-is-long-enough-1234567890",
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "initialize",
      params: {
        protocolVersion: "2025-03-26",
        capabilities: {},
        clientInfo: { name: "uploaded-plugin-test", version: "1.0.0" },
      },
    }),
  });
  assert.equal(legacyInitialize.status, 200, "legacy uploaded-plugin endpoint");
  const legacyInitialized = await legacyInitialize.json();
  assert.equal(legacyInitialized.result.serverInfo.name, "prime-p6-remote");

  const legacyStatus = await fetch(base + "/mcp", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept": "application/json, text/event-stream",
      "authorization": "Bearer legacy-test-bearer-that-is-long-enough-1234567890",
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: { name: "get_phone_status", arguments: {} },
    }),
  });
  assert.equal(legacyStatus.status, 200);
  const legacyStatusBody = await legacyStatus.json();
  assert.equal(legacyStatusBody.result.isError ?? false, false);
  assert.match(
    legacyStatusBody.result.content[0].text,
    /"connected":false/
  );
});
