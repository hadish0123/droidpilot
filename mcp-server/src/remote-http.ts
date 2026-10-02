import { createHmac, randomInt, randomUUID, timingSafeEqual } from "node:crypto";
import http, { IncomingMessage, ServerResponse } from "node:http";
import { WebSocketServer } from "ws";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { RemotePhoneHub } from "./remote-phone-hub.js";
import { createRemoteServer } from "./remote-server.js";

const port = Number(process.env.PORT || 3000);
const mcpBearer = (process.env.PRIME_MCP_BEARER || "").trim();
const pairingSecret = (process.env.PRIME_PAIRING_SECRET || "").trim();
const publicBaseUrl = (process.env.PRIME_PUBLIC_BASE_URL || "").replace(/\/$/, "");

if (mcpBearer.length < 32) throw new Error("PRIME_MCP_BEARER must be at least 32 characters");
if (pairingSecret.length < 32) throw new Error("PRIME_PAIRING_SECRET must be at least 32 characters");

const hub = new RemotePhoneHub();
const pairCodes = new Map<string, number>();
const wsServer = new WebSocketServer({ noServer: true });

function json(res: ServerResponse, status: number, body: unknown) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(payload),
    "cache-control": "no-store",
  });
  res.end(payload);
}

async function readJson(req: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    const part = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += part.length;
    if (size > 2_000_000) throw new Error("Request too large");
    chunks.push(part);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

function createPairingCode() {
  let code = "";
  do code = String(randomInt(100000, 1000000)); while (pairCodes.has(code));
  pairCodes.set(code, Date.now() + 10 * 60_000);
  return {
    code,
    expiresInSeconds: 600,
    relayUrl: publicBaseUrl || undefined,
  };
}

function consumePairingCode(code: string): boolean {
  const expires = pairCodes.get(code);
  pairCodes.delete(code);
  return Boolean(expires && expires > Date.now());
}

function signDevice(deviceId: string): string {
  const signature = createHmac("sha256", pairingSecret).update(deviceId).digest("hex");
  return `${deviceId}.${signature}`;
}

function verifyDeviceCredential(token: string): string | null {
  const dot = token.lastIndexOf(".");
  if (dot < 1) return null;
  const deviceId = token.slice(0, dot);
  const supplied = Buffer.from(token.slice(dot + 1), "utf8");
  const expected = Buffer.from(createHmac("sha256", pairingSecret).update(deviceId).digest("hex"), "utf8");
  return supplied.length === expected.length && timingSafeEqual(supplied, expected) ? deviceId : null;
}

function bearer(req: IncomingMessage): string {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", "http://localhost");

    if (url.pathname === "/health") {
      json(res, 200, { ok: true, service: "prime-p6-remote-mcp", phoneConnected: hub.connected });
      return;
    }

    if (url.pathname === "/phone/pair" && req.method === "POST") {
      const body = await readJson(req);
      const code = String(body?.code || "").trim();
      const deviceId = String(body?.deviceId || "").trim();
      if (!/^[a-zA-Z0-9_-]{8,128}$/.test(deviceId) || !consumePairingCode(code)) {
        json(res, 401, { error: "Invalid or expired pairing code" });
        return;
      }
      json(res, 200, { credential: signDevice(deviceId) });
      return;
    }

    if (url.pathname !== "/mcp") {
      json(res, 404, { error: "Not found" });
      return;
    }

    const auth = bearer(req);
    const supplied = Buffer.from(auth, "utf8");
    const expected = Buffer.from(mcpBearer, "utf8");
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      json(res, 401, { error: "Unauthorized" });
      return;
    }

    const body = req.method === "POST" ? await readJson(req) : undefined;
    const mcp = createRemoteServer(hub, createPairingCode);
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
    });
    await mcp.connect(transport);
    try {
      await transport.handleRequest(req, res, body);
    } finally {
      await mcp.close();
    }
  } catch (error) {
    if (!res.headersSent) json(res, 500, { error: (error as Error).message });
    else res.end();
  }
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname !== "/phone") {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }
  const deviceId = verifyDeviceCredential(bearer(req));
  if (!deviceId) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
    return;
  }
  wsServer.handleUpgrade(req, socket, head, (ws) => {
    hub.attach(ws, deviceId);
  });
});

server.listen(port, "0.0.0.0", () => {
  console.error(`PRIME P6 remote MCP listening on :${port}`);
});
