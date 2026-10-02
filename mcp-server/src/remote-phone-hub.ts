import { WebSocket } from "ws";

export interface PhoneResponse {
  id: string | null;
  success: boolean;
  data?: Record<string, unknown>;
  error?: string;
}

type PendingRequest = {
  identity: string;
  resolve: (value: PhoneResponse) => void;
  reject: (reason: Error) => void;
  timer: NodeJS.Timeout;
};

export class RemotePhoneHub {
  private sockets = new Map<string, WebSocket>();
  private pending = new Map<string, PendingRequest>();
  private counter = 0;

  private resolveIdentity(identity: string): string | null {
    const exact = this.sockets.get(identity);
    if (exact?.readyState === WebSocket.OPEN) return identity;

    // Compatibility path for the user's private uploaded plugin. It is only
    // allowed when exactly one PRIME phone is online, so it cannot select a
    // different device in a multi-device setup.
    if (identity === "legacy") {
      const online = [...this.sockets.entries()]
        .filter(([, socket]) => socket.readyState === WebSocket.OPEN)
        .map(([id]) => id);
      return online.length === 1 ? online[0] : null;
    }
    return null;
  }

  isConnected(identity: string): boolean {
    return this.resolveIdentity(identity) !== null;
  }

  attach(socket: WebSocket, identity: string) {
    const previous = this.sockets.get(identity);
    if (previous && previous !== socket) {
      try { previous.close(4002, "Replaced by newer PRIME connection"); } catch {}
    }
    this.sockets.set(identity, socket);
    this.mostRecentIdentity = identity;

    socket.on("message", (raw) => {
      try {
        const response = JSON.parse(raw.toString()) as PhoneResponse;
        if (!response?.id) return;
        const item = this.pending.get(response.id);
        if (!item || item.identity !== identity) return;
        clearTimeout(item.timer);
        this.pending.delete(response.id);
        item.resolve(response);
      } catch {
        // Ignore malformed device responses; the matching request times out.
      }
    });

    socket.on("close", () => {
      if (this.sockets.get(identity) === socket) {
        this.sockets.delete(identity);
        for (const [id, item] of this.pending) {
          if (item.identity !== identity) continue;
          clearTimeout(item.timer);
          item.reject(new Error("PRIME phone disconnected"));
          this.pending.delete(id);
        }
      }
    });
  }

  async sendCommand(
    identity: string,
    command: string,
    params?: Record<string, unknown>,
    timeoutMs = 12000
  ): Promise<PhoneResponse> {
    const resolvedIdentity = this.resolveIdentity(identity);
    if (!resolvedIdentity) {
      throw new Error("PRIME phone is not connected to the remote bridge");
    }

    const socket = this.sockets.get(resolvedIdentity);
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("PRIME phone is not connected to the remote bridge");
    }

    const id = `remote-${Date.now()}-${++this.counter}`;
    const payload = JSON.stringify({ id, command, params });

    return new Promise<PhoneResponse>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Phone command timed out: ${command}`));
      }, timeoutMs);
      this.pending.set(id, { identity: resolvedIdentity, resolve, reject, timer });
      socket.send(payload, (error) => {
        if (!error) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      });
    });
  }
}
