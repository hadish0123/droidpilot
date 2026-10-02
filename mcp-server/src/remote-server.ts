import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { RemotePhoneHub } from "./remote-phone-hub.js";
import { toolDefinitions } from "./tools.js";

type ToolResult = {
  content: Array<
    | { type: "text"; text: string }
    | { type: "image"; data: string; mimeType: string }
  >;
  isError?: boolean;
};

export function createRemoteServer(
  hub: RemotePhoneHub,
  identity: string
): McpServer {
  const server = new McpServer({ name: "prime-p6-remote", version: "6.0.0" });

  async function sendAndFormat(
    command: string,
    params?: Record<string, unknown>,
    timeoutMs?: number
  ): Promise<ToolResult> {
    try {
      const response = await hub.sendCommand(identity, command, params, timeoutMs);
      if (!response.success) {
        return { content: [{ type: "text", text: `Error: ${response.error || "Phone action failed"}` }], isError: true };
      }
      if (command === "screenshot" && response.data?.image) {
        return {
          content: [
            {
              type: "image",
              data: String(response.data.image),
              mimeType: `image/${String(response.data.format || "jpeg")}`,
            },
            {
              type: "text",
              text: `Screenshot captured: ${String(response.data.width || "?")}x${String(response.data.height || "?")}`,
            },
          ],
        };
      }
      return {
        content: [{ type: "text", text: JSON.stringify(response.data ?? {}, null, 2) }],
      };
    } catch (error) {
      return {
        content: [{ type: "text", text: (error as Error).message }],
        isError: true,
      };
    }
  }

  server.tool(
    "get_phone_status",
    "Check whether this private PRIME Android device is connected to the remote bridge.",
    {},
    async () => ({
      content: [{
        type: "text",
        text: JSON.stringify({
          connected: hub.isConnected(identity),
        }),
      }],
    })
  );

  server.tool("get_device_info", toolDefinitions.get_device_info.description, {}, async () => sendAndFormat("get_device_info"));
  server.tool("screenshot", toolDefinitions.screenshot.description, { quality: toolDefinitions.screenshot.inputSchema.quality }, async ({ quality }) => sendAndFormat("screenshot", { quality }, 15000));
  server.tool("get_ui_tree", toolDefinitions.get_ui_tree.description, { maxDepth: toolDefinitions.get_ui_tree.inputSchema.maxDepth }, async ({ maxDepth }) => sendAndFormat("get_ui_tree", { maxDepth }));
  server.tool("find_element", toolDefinitions.find_element.description, {
    text: toolDefinitions.find_element.inputSchema.text,
    id: toolDefinitions.find_element.inputSchema.id,
    className: toolDefinitions.find_element.inputSchema.className,
    contentDescription: toolDefinitions.find_element.inputSchema.contentDescription,
    maxResults: toolDefinitions.find_element.inputSchema.maxResults,
  }, async (params) => sendAndFormat("find_element", params));
  server.tool("tap", toolDefinitions.tap.description, {
    x: toolDefinitions.tap.inputSchema.x,
    y: toolDefinitions.tap.inputSchema.y,
    duration: toolDefinitions.tap.inputSchema.duration,
  }, async (params) => sendAndFormat("tap", params));
  server.tool("long_press", toolDefinitions.long_press.description, {
    x: toolDefinitions.long_press.inputSchema.x,
    y: toolDefinitions.long_press.inputSchema.y,
    duration: toolDefinitions.long_press.inputSchema.duration,
  }, async (params) => sendAndFormat("long_press", params));
  server.tool("swipe", toolDefinitions.swipe.description, {
    startX: toolDefinitions.swipe.inputSchema.startX,
    startY: toolDefinitions.swipe.inputSchema.startY,
    endX: toolDefinitions.swipe.inputSchema.endX,
    endY: toolDefinitions.swipe.inputSchema.endY,
    duration: toolDefinitions.swipe.inputSchema.duration,
  }, async (params) => sendAndFormat("swipe", params));
  server.tool("scroll", toolDefinitions.scroll.description, {
    direction: toolDefinitions.scroll.inputSchema.direction,
    amount: toolDefinitions.scroll.inputSchema.amount,
  }, async (params) => sendAndFormat("scroll", params));
  server.tool("type_text", toolDefinitions.type_text.description, { text: toolDefinitions.type_text.inputSchema.text }, async (params) => sendAndFormat("type_text", params));
  server.tool("set_text", toolDefinitions.set_text.description, { text: toolDefinitions.set_text.inputSchema.text }, async (params) => sendAndFormat("set_text", params));
  server.tool("press_key", toolDefinitions.press_key.description, { key: toolDefinitions.press_key.inputSchema.key }, async (params) => sendAndFormat("press_key", params));
  server.tool("click_element", toolDefinitions.click_element.description, {
    text: toolDefinitions.click_element.inputSchema.text,
    id: toolDefinitions.click_element.inputSchema.id,
    contentDescription: toolDefinitions.click_element.inputSchema.contentDescription,
  }, async (params) => sendAndFormat("click_element", params));
  server.tool("wait_for_element", toolDefinitions.wait_for_element.description, {
    text: toolDefinitions.wait_for_element.inputSchema.text,
    id: toolDefinitions.wait_for_element.inputSchema.id,
    className: toolDefinitions.wait_for_element.inputSchema.className,
    contentDescription: toolDefinitions.wait_for_element.inputSchema.contentDescription,
    timeout: toolDefinitions.wait_for_element.inputSchema.timeout,
  }, async (params) => sendAndFormat("wait_for_element", params, (params.timeout ?? 10000) + 5000));
  server.tool("open_app", toolDefinitions.open_app.description, { package: toolDefinitions.open_app.inputSchema.package }, async (params) => sendAndFormat("open_app", { package: params.package }));
  server.tool("pinch", toolDefinitions.pinch.description, {
    x: toolDefinitions.pinch.inputSchema.x,
    y: toolDefinitions.pinch.inputSchema.y,
    scale: toolDefinitions.pinch.inputSchema.scale,
    duration: toolDefinitions.pinch.inputSchema.duration,
  }, async (params) => sendAndFormat("pinch", params));
  server.tool("get_focused", toolDefinitions.get_focused.description, {}, async () => sendAndFormat("get_focused"));

  return server;
}
