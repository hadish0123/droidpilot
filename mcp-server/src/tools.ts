import { z } from "zod";

export const toolDefinitions = {
  connect: {
    description:
      "Connect to the Android device running PRIME P6. Must be called before any other commands.",
    inputSchema: {
      host: z
        .string()
        .describe("IP address of the Android device (e.g. 192.168.1.100)"),
      port: z
        .number()
        .default(8765)
        .describe("WebSocket port (default: 8765)"),
      authToken: z
        .string()
        .optional()
        .describe("Optional authentication token"),
    },
  },

  disconnect: {
    description: "Disconnect from the Android device.",
    inputSchema: {},
  },

  get_device_info: {
    description:
      "Get device information including manufacturer, model, screen resolution, and Android version.",
    inputSchema: {},
  },

  screenshot: {
    description:
      "Take a screenshot of the current screen. Returns a base64-encoded JPEG image. IMPORTANT: Prefer `get_ui_tree` (UI dump) over screenshots whenever possible — UI dumps are far more token-efficient, structured, and reliable for element lookup and automation. Only use screenshots when visual inspection is strictly required (e.g. verifying rendering, images, or non-accessible content).",
    inputSchema: {
      quality: z
        .number()
        .min(1)
        .max(100)
        .default(80)
        .describe("JPEG quality (1-100, default: 80)"),
    },
  },

  get_ui_tree: {
    description:
      "Get the full UI hierarchy tree of the current screen. Returns all visible elements with their properties (text, bounds, clickable, etc). PREFERRED over `screenshot` for understanding screen state — use this first for element discovery, automation, and assertions.",
    inputSchema: {
      maxDepth: z
        .number()
        .default(15)
        .describe("Maximum depth of UI tree traversal (default: 15)"),
    },
  },

  find_element: {
    description:
      "Find UI elements matching given criteria. Search by text content, resource ID, class name, or content description.",
    inputSchema: {
      text: z.string().optional().describe("Text content to search for (partial match)"),
      id: z.string().optional().describe("Resource ID to search for (partial match)"),
      className: z.string().optional().describe("Class name to search for (e.g. android.widget.Button)"),
      contentDescription: z.string().optional().describe("Content description to search for"),
      maxResults: z.number().default(10).describe("Maximum number of results"),
    },
  },

  tap: {
    description: "Tap at specific screen coordinates.",
    inputSchema: {
      x: z.number().describe("X coordinate"),
      y: z.number().describe("Y coordinate"),
      duration: z.number().default(100).describe("Tap duration in ms (default: 100)"),
    },
  },

  long_press: {
    description: "Long press at specific screen coordinates.",
    inputSchema: {
      x: z.number().describe("X coordinate"),
      y: z.number().describe("Y coordinate"),
      duration: z.number().default(1000).describe("Press duration in ms (default: 1000)"),
    },
  },

  swipe: {
    description: "Perform a swipe gesture from one point to another.",
    inputSchema: {
      startX: z.number().describe("Start X coordinate"),
      startY: z.number().describe("Start Y coordinate"),
      endX: z.number().describe("End X coordinate"),
      endY: z.number().describe("End Y coordinate"),
      duration: z.number().default(300).describe("Swipe duration in ms (default: 300)"),
    },
  },

  scroll: {
    description: "Scroll the screen in a direction.",
    inputSchema: {
      direction: z
        .enum(["up", "down", "left", "right"])
        .describe("Scroll direction"),
      amount: z
        .number()
        .default(500)
        .describe("Scroll distance in pixels (default: 500)"),
    },
  },

  type_text: {
    description:
      "Type text into the currently focused input field. Appends to existing text.",
    inputSchema: {
      text: z.string().describe("Text to type"),
    },
  },

  set_text: {
    description:
      "Set (replace) the text of the currently focused input field.",
    inputSchema: {
      text: z.string().describe("Text to set"),
    },
  },

  press_key: {
    description:
      "Press a system key/button (back, home, recents, notifications, quick_settings, power_dialog, lock_screen, take_screenshot).",
    inputSchema: {
      key: z
        .enum([
          "back",
          "home",
          "recents",
          "notifications",
          "quick_settings",
          "power_dialog",
          "lock_screen",
          "take_screenshot",
        ])
        .describe("Key to press"),
    },
  },

  click_element: {
    description:
      "Find and click a UI element by text, ID, or content description. More reliable than coordinate-based tapping.",
    inputSchema: {
      text: z.string().optional().describe("Text of the element to click"),
      id: z.string().optional().describe("Resource ID of the element"),
      contentDescription: z.string().optional().describe("Content description of the element"),
    },
  },

  wait_for_element: {
    description:
      "Wait for a UI element to appear on screen. Useful for waiting after navigation or loading.",
    inputSchema: {
      text: z.string().optional().describe("Text to wait for"),
      id: z.string().optional().describe("Resource ID to wait for"),
      className: z.string().optional().describe("Class name to wait for"),
      contentDescription: z.string().optional().describe("Content description to wait for"),
      timeout: z
        .number()
        .default(10000)
        .describe("Timeout in milliseconds (default: 10000)"),
    },
  },

  open_app: {
    description: "Open an app by its package name.",
    inputSchema: {
      package: z
        .string()
        .describe(
          "Package name of the app (e.g. com.android.chrome, com.google.android.apps.maps)"
        ),
    },
  },

  pinch: {
    description:
      "Perform a pinch gesture for zoom in/out. Scale > 1 zooms in, < 1 zooms out.",
    inputSchema: {
      x: z.number().optional().describe("Center X coordinate (default: screen center)"),
      y: z.number().optional().describe("Center Y coordinate (default: screen center)"),
      scale: z
        .number()
        .default(1.5)
        .describe("Scale factor (>1 zoom in, <1 zoom out, default: 1.5)"),
      duration: z.number().default(400).describe("Duration in ms (default: 400)"),
    },
  },

  get_focused: {
    description: "Get information about the currently focused input element.",
    inputSchema: {},
  },
} as const;
