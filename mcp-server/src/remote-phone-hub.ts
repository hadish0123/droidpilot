import { WebSocket } from "ws";

export interface PhoneResponse {
  id: string | null;
  success: boolean;
  data?: Record<string, unknown>;
  error?: string;
}

export class RemotePhoneHub {
  private socket: WebSocket | null = null;
  private deviceId: string | null = null;
  private pending = new Map<string, {
    resolve: (value: PhoneResponse) => void;
    reject: (reason: Error) => void;
    timer: NodeJS.Timeout;
  }>();
  private counter = 0;

  get connected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN;
  }

  get connectedDeviceId(): string | null {
    return this.connected ? this.deviceId : null;
  }

  attach(socket: WebSocket, deviceId: string) {
    if (this.socket && this.socket !== socket) {
      try { this.socket.close(4002, "Replaced by newer PRIME connection"); } catch {}
    }
    this.socket = socket;
    this.deviceId = deviceId;

    socket.on("message", (raw) => {
      try {
        const response = JSON.parse(raw.toString()) as PhoneResponse;
        if (!response?.id) return;
        const item = this.pending.get(response.id);
        if (!item) return;
        clearTimeout(item.timer);
        this.pending.delete(response.id);
        item.resolve(response);
      } catch {
        // Ignore malformed device responses; the matching request times out.
      }
    });

    socket.on("close", () => {
      if (this.socket === socket) {
        this.socket = null;
        this.deviceId = null;
        for (const [, item] of this.pending) {
          clearTimeout(item.timer);
          item.reject(new Error("PRIME phone disconnected"));
        }
        this.pending.clear();
      }
    });
  }

  async sendCommand(
    command: string,
    params?: Record<string, unknown>,
    timeoutMs = 12000
  ): Promise<PhoneResponse> {
    const socket = this.socket;
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
      this.pending.set(id, { resolve, reject, timer });
      socket.send(payload, (error) => {
        if (!error) return;
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      });
    });
  }
}
