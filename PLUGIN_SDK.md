# PRIME Plugin SDK v1

PRIME plugins are declarative integrations. A plugin does **not** ship Android
bytecode or execute arbitrary local code. Version 1 maps a validated manifest to
an MCP Streamable HTTP server, then PRIME routes discovered capabilities through
its existing MCP client, namespace isolation, risk policy, and confirmation gate.

## Manifest

Create a UTF-8 JSON file matching `prime-plugin.schema.json`.

```json
{
  "schema": "prime.plugin.v1",
  "id": "com.example.docs",
  "name": "Example Docs",
  "version": "1.0.0",
  "description": "Search internal documentation",
  "runtime": {
    "type": "mcp",
    "url": "https://example.com/mcp"
  },
  "capabilities": ["tools", "resources", "prompts"]
}
```

Remote plugin endpoints must use HTTPS. Cleartext HTTP is accepted only for
literal loopback development endpoints. Do not put bearer tokens, passwords,
API keys, or other secrets in the manifest.

## Installation lifecycle

1. In PRIME, open **+ → Plugins → Install**.
2. Select the manifest JSON.
3. PRIME validates schema, ID, endpoint and declared capabilities.
4. If the server needs a bearer token, enter it in the separate secret field.
5. PRIME stores plugin metadata and the MCP credential using Android
   Keystore-backed encrypted storage.
6. PRIME performs MCP discovery. Discovered tools become available to the agent
   under collision-safe namespaced command IDs.

Plugins can be disabled, re-enabled, refreshed, upgraded by reinstalling the same
plugin ID, or uninstalled. Upgrading without entering a new token preserves the
previous encrypted token.

## Tool safety contract

MCP tool annotations are hints, not authorization. PRIME applies these minimums:

- `readOnlyHint: true` → READ.
- `destructiveHint: true` → DESTRUCTIVE.
- No usable annotation → SENSITIVE.

Sensitive and destructive calls require user confirmation. Plugin tool results
are treated as untrusted data and cannot override PRIME instructions.

## Compatibility

The Android MCP client negotiates the current PRIME-supported modern protocol
and falls back to supported 2025-era Streamable HTTP handshakes. Plugin authors
should return standard MCP `tools/list`, `resources/list`, and `prompts/list`
responses and use standard annotations where applicable.
