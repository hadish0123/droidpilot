import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import http, { IncomingMessage, ServerResponse } from "node:http";
import { WebSocketServer } from "ws";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { RemotePhoneHub } from "./remote-phone-hub.js";
import { createRemoteServer } from "./remote-server.js";

const port = Number(process.env.PORT || 3000);
const mcpBearer = (process.env.PRIME_MCP_BEARER || "").trim();
const pairingSecret = (process.env.PRIME_PAIRING_SECRET || "").trim();
const publicBaseUrl = (process.env.PRIME_PUBLIC_BASE_URL || "").replace(/\/$/, "");

if (pairingSecret.length < 32) {
  throw new Error("PRIME_PAIRING_SECRET must be at least 32 characters");
}

const hub = new RemotePhoneHub();
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

function identityFromProof(deviceId: string, deviceProof: string): string {
  const proofHash = createHash("sha256").update(deviceProof).digest("hex").slice(0, 32);
  return deviceId + "_" + proofHash;
}

function signToken(identity: string, purpose: "device" | "mcp"): string {
  const signature = createHmac("sha256", pairingSecret)
    .update(purpose + ":" + identity)
    .digest("hex");
  return identity + "." + signature;
}

function verifyToken(token: string, purpose: "device" | "mcp"): string | null {
  const dot = token.lastIndexOf(".");
  if (dot < 1) return null;

  const identity = token.slice(0, dot);
  if (!/^[a-zA-Z0-9_-]{16,180}$/.test(identity)) return null;

  const supplied = Buffer.from(token.slice(dot + 1), "utf8");
  const expected = Buffer.from(
    createHmac("sha256", pairingSecret)
      .update(purpose + ":" + identity)
      .digest("hex"),
    "utf8"
  );

  return supplied.length === expected.length &&
    timingSafeEqual(supplied, expected)
    ? identity
    : null;
}

function bearer(req: IncomingMessage): string {
  const header = req.headers.authorization || "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

function legacyBearerAuthorized(req: IncomingMessage): boolean {
  if (mcpBearer.length < 32) return false;
  const supplied = Buffer.from(bearer(req), "utf8");
  const expected = Buffer.from(mcpBearer, "utf8");
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || "/", "http://localhost");

    if (url.pathname === "/health") {
      json(res, 200, {
        ok: true,
        service: "prime-p6-remote-mcp",
      });
      return;
    }

    if (url.pathname === "/phone/pair" && req.method === "POST") {
      const body = await readJson(req);
      const deviceId = String(body?.deviceId || "").trim();
      const deviceProof = String(body?.deviceProof || "").trim();

      if (
        !/^[a-zA-Z0-9_-]{8,128}$/.test(deviceId) ||
        deviceProof.length < 32 ||
        deviceProof.length > 512
      ) {
        json(res, 400, { error: "Invalid device registration request" });
        return;
      }

      const identity = identityFromProof(deviceId, deviceProof);
      const credential = signToken(identity, "device");
      const mcpToken = signToken(identity, "mcp");

      json(res, 200, {
        credential,
        mcpUrl: publicBaseUrl
          ? publicBaseUrl + "/mcp/" + mcpToken
          : undefined,
      });
      return;
    }

    let mcpIdentity: string | null = null;
    if (url.pathname === "/mcp") {
      if (legacyBearerAuthorized(req)) mcpIdentity = "legacy";
    } else if (url.pathname.startsWith("/mcp/")) {
      const token = decodeURIComponent(url.pathname.slice("/mcp/".length));
      mcpIdentity = verifyToken(token, "mcp");
    } else {
      json(res, 404, { error: "Not found" });
      return;
    }

    if (!mcpIdentity) {
      json(res, 401, { error: "Unauthorized" });
      return;
    }

    const body = req.method === "POST" ? await readJson(req) : undefined;
    const mcp = createRemoteServer(hub, mcpIdentity);
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
    if (!res.headersSent) {
      json(res, 500, { error: (error as Error).message });
    } else {
      res.end();
    }
  }
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url || "/", "http://localhost");
  if (url.pathname !== "/phone") {
    socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
    socket.destroy();
    return;
  }

  const identity = verifyToken(bearer(req), "device");
  if (!identity) {
    socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
    socket.destroy();
    return;
  }

  wsServer.handleUpgrade(req, socket, head, (ws) => {
    hub.attach(ws, identity);
  });
});

server.listen(port, "0.0.0.0", () => {
  console.error("PRIME P6 remote MCP listening on :" + port);
});
