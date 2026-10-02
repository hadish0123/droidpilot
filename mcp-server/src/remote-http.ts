import { randomUUID } from "node:crypto";
import http from "node:http";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createServer } from "./server.js";

/**
 * Optional Streamable HTTP entry point for ChatGPT-compatible remote MCP hosts.
 * The existing stdio entry point remains unchanged.
 *
 * Set PRIME_MCP_BEARER to a strong secret and put this process behind HTTPS.
 * A fresh McpServer/transport pair is created per initialize request, avoiding
 * shared-session state between callers.
 */
const port = Number(process.env.PORT || 3000);
const bearer = (process.env.PRIME_MCP_BEARER || "").trim();
if (bearer.length < 32) throw new Error("PRIME_MCP_BEARER must be at least 32 characters");

const sessions = new Map<string, { server: McpServer; transport: StreamableHTTPServerTransport }>();

function authorized(req: http.IncomingMessage): boolean {
  return req.headers.authorization === `Bearer ${bearer}`;
}

const app = http.createServer(async (req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, service: "prime-p6-remote-mcp" }));
    return;
  }
  if (req.url !== "/mcp" || !authorized(req)) {
    res.writeHead(authorized(req) ? 404 : 401);
    res.end();
    return;
  }

  const sessionId = req.headers["mcp-session-id"] as string | undefined;
  let entry = sessionId ? sessions.get(sessionId) : undefined;

  if (!entry && req.method === "POST") {
    const server = createServer();
    let transport: StreamableHTTPServerTransport;\n    transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: () => randomUUID(),
      onsessioninitialized: (id): void => { sessions.set(id, { server, transport }); },
    });
    transport.onclose = () => {
      if (transport.sessionId) sessions.delete(transport.sessionId);
    };
    await server.connect(transport);
    entry = { server, transport };
  }

  if (!entry) {
    res.writeHead(400);
    res.end("Unknown or missing MCP session");
    return;
  }
  await entry.transport.handleRequest(req, res);
});

app.listen(port, "0.0.0.0", () => {
  console.error(`PRIME P6 remote MCP listening on :${port}/mcp`);
});
