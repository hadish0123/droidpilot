import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { AndroidClient } from "./android-client.js";
import { toolDefinitions } from "./tools.js";

type TextContent = { type: "text"; text: string };
type ImageContent = { type: "image"; data: string; mimeType: string };
type ToolResult = { content: (TextContent | ImageContent)[]; isError?: boolean };

export function createServer(): McpServer {
  const server = new McpServer({
    name: "prime-p6",
    version: "6.0.0",
  });

  let client: AndroidClient | null = null;

  function ensureConnected(): AndroidClient {
    if (!client || !client.connected) {
      throw new Error(
        "Not connected to Android device. Use the 'connect' tool first."
      );
    }
    return client;
  }

  async function sendAndFormat(
    command: string,
    params?: Record<string, unknown>,
    timeoutMs?: number
  ): Promise<ToolResult> {
    const c = ensureConnected();
    const response = await c.sendCommand(command, params, timeoutMs);

    if (!response.success) {
      return {
        content: [{ type: "text", text: `Error: ${response.error}` }],
        isError: true,
      };
    }

    // Handle screenshot specially - return as image content
    if (command === "screenshot" && response.data?.image) {
      const image = response.data.image as string;
      const width = response.data.width as number;
      const height = response.data.height as number;
      const format = response.data.format as string;
      return {
        content: [
          {
            type: "image",
            data: image,
            mimeType: `image/${format}`,
          },
          {
            type: "text",
            text: `Screenshot captured: ${width}x${height}`,
          },
        ],
      };
    }

    return {
      content: [
        {
          type: "text",
          text: JSON.stringify(response.data, null, 2),
        },
      ],
    };
  }

  // --- Connection tools ---

  server.tool(
    "connect",
    toolDefinitions.connect.description,
    {
      host: toolDefinitions.connect.inputSchema.host,
      port: toolDefinitions.connect.inputSchema.port,
      authToken: toolDefinitions.connect.inputSchema.authToken,
    },
    async ({ host, port, authToken }): Promise<ToolResult> => {
      client?.disconnect();

      client = new AndroidClient(host, port, authToken);

      try {
        await client.connect();

        const pingResp = await client.sendCommand("ping");
        if (!pingResp.success) {
          throw new Error("Ping failed");
        }

        const infoResp = await client.sendCommand("get_device_info");
        const info = infoResp.data || {};

        return {
          content: [
            {
              type: "text",
              text: `Connected to ${info.manufacturer} ${info.model} (Android ${info.release}, ${info.screenWidth}x${info.screenHeight})`,
            },
          ],
        };
      } catch (e) {
        client?.disconnect();
        client = null;
        return {
          content: [
            {
              type: "text",
              text: `Failed to connect to ${host}:${port} - ${(e as Error).message}`,
            },
          ],
          isError: true,
        };
      }
    }
  );

  server.tool(
    "disconnect",
    toolDefinitions.disconnect.description,
    {},
    async (): Promise<ToolResult> => {
      if (client) {
        client.disconnect();
        client = null;
      }
      return {
        content: [{ type: "text", text: "Disconnected" }],
      };
    }
  );

  server.tool(
    "get_device_info",
    toolDefinitions.get_device_info.description,
    {},
    async (): Promise<ToolResult> => sendAndFormat("get_device_info")
  );

  server.tool(
    "screenshot",
    toolDefinitions.screenshot.description,
    { quality: toolDefinitions.screenshot.inputSchema.quality },
    async ({ quality }): Promise<ToolResult> =>
      sendAndFormat("screenshot", { quality }, 15000)
  );

  server.tool(
    "get_ui_tree",
    toolDefinitions.get_ui_tree.description,
    { maxDepth: toolDefinitions.get_ui_tree.inputSchema.maxDepth },
    async ({ maxDepth }): Promise<ToolResult> =>
      sendAndFormat("get_ui_tree", { maxDepth })
  );

  server.tool(
    "find_element",
    toolDefinitions.find_element.description,
    {
      text: toolDefinitions.find_element.inputSchema.text,
      id: toolDefinitions.find_element.inputSchema.id,
      className: toolDefinitions.find_element.inputSchema.className,
      contentDescription:
        toolDefinitions.find_element.inputSchema.contentDescription,
      maxResults: toolDefinitions.find_element.inputSchema.maxResults,
    },
    async (params): Promise<ToolResult> => sendAndFormat("find_element", params)
  );

  server.tool(
    "tap",
    toolDefinitions.tap.description,
    {
      x: toolDefinitions.tap.inputSchema.x,
      y: toolDefinitions.tap.inputSchema.y,
      duration: toolDefinitions.tap.inputSchema.duration,
    },
    async (params): Promise<ToolResult> => sendAndFormat("tap", params)
  );

  server.tool(
    "long_press",
    toolDefinitions.long_press.description,
    {
      x: toolDefinitions.long_press.inputSchema.x,
      y: toolDefinitions.long_press.inputSchema.y,
      duration: toolDefinitions.long_press.inputSchema.duration,
    },
    async (params): Promise<ToolResult> => sendAndFormat("long_press", params)
  );

  server.tool(
    "swipe",
    toolDefinitions.swipe.description,
    {
      startX: toolDefinitions.swipe.inputSchema.startX,
      startY: toolDefinitions.swipe.inputSchema.startY,
      endX: toolDefinitions.swipe.inputSchema.endX,
      endY: toolDefinitions.swipe.inputSchema.endY,
      duration: toolDefinitions.swipe.inputSchema.duration,
    },
    async (params): Promise<ToolResult> => sendAndFormat("swipe", params)
  );

  server.tool(
    "scroll",
    toolDefinitions.scroll.description,
    {
      direction: toolDefinitions.scroll.inputSchema.direction,
      amount: toolDefinitions.scroll.inputSchema.amount,
    },
    async (params): Promise<ToolResult> => sendAndFormat("scroll", params)
  );

  server.tool(
    "type_text",
    toolDefinitions.type_text.description,
    { text: toolDefinitions.type_text.inputSchema.text },
    async (params): Promise<ToolResult> => sendAndFormat("type_text", params)
  );

  server.tool(
    "set_text",
    toolDefinitions.set_text.description,
    { text: toolDefinitions.set_text.inputSchema.text },
    async (params): Promise<ToolResult> => sendAndFormat("set_text", params)
  );

  server.tool(
    "press_key",
    toolDefinitions.press_key.description,
    { key: toolDefinitions.press_key.inputSchema.key },
    async (params): Promise<ToolResult> => sendAndFormat("press_key", params)
  );

  server.tool(
    "click_element",
    toolDefinitions.click_element.description,
    {
      text: toolDefinitions.click_element.inputSchema.text,
      id: toolDefinitions.click_element.inputSchema.id,
      contentDescription:
        toolDefinitions.click_element.inputSchema.contentDescription,
    },
    async (params): Promise<ToolResult> =>
      sendAndFormat("click_element", params)
  );

  server.tool(
    "wait_for_element",
    toolDefinitions.wait_for_element.description,
    {
      text: toolDefinitions.wait_for_element.inputSchema.text,
      id: toolDefinitions.wait_for_element.inputSchema.id,
      className: toolDefinitions.wait_for_element.inputSchema.className,
      contentDescription:
        toolDefinitions.wait_for_element.inputSchema.contentDescription,
      timeout: toolDefinitions.wait_for_element.inputSchema.timeout,
    },
    async (params): Promise<ToolResult> =>
      sendAndFormat(
        "wait_for_element",
        params,
        (params.timeout ?? 10000) + 5000
      )
  );

  server.tool(
    "open_app",
    toolDefinitions.open_app.description,
    { package: toolDefinitions.open_app.inputSchema.package },
    async (params): Promise<ToolResult> =>
      sendAndFormat("open_app", { package: params.package })
  );

  server.tool(
    "pinch",
    toolDefinitions.pinch.description,
    {
      x: toolDefinitions.pinch.inputSchema.x,
      y: toolDefinitions.pinch.inputSchema.y,
      scale: toolDefinitions.pinch.inputSchema.scale,
      duration: toolDefinitions.pinch.inputSchema.duration,
    },
    async (params): Promise<ToolResult> => sendAndFormat("pinch", params)
  );

  server.tool(
    "get_focused",
    toolDefinitions.get_focused.description,
    {},
    async (): Promise<ToolResult> => sendAndFormat("get_focused")
  );

  return server;
}
