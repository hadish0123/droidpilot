import WebSocket from "ws";
import { EventEmitter } from "events";

export interface CommandRequest {
  id: string;
  command: string;
  params?: Record<string, unknown>;
}

export interface CommandResponse {
  id: string | null;
  success: boolean;
  data?: Record<string, unknown>;
  error?: string;
}

export class AndroidClient extends EventEmitter {
  private ws: WebSocket | null = null;
  private connecting: Promise<void> | null = null;
  private pendingRequests = new Map<string, {
    resolve: (value: CommandResponse) => void;
    reject: (reason: Error) => void;
    timer: NodeJS.Timeout;
  }>();
  private requestCounter = 0;
  private _connected = false;

  constructor(private deviceHost: string, private devicePort: number, private authToken?: string) {
    super();
    if (!/^[a-zA-Z0-9.:-]+$/.test(deviceHost) || !Number.isInteger(devicePort) || devicePort < 1 || devicePort > 65535) {
      throw new Error("Invalid Android device host or port");
    }
  }

  get connected(): boolean { return this._connected; }
  get url(): string {
    const host = this.deviceHost.includes(":") ? `[${this.deviceHost}]` : this.deviceHost;
    return `ws://${host}:${this.devicePort}`;
  }

  private reportError(error: Error): void {
    // EventEmitter treats an unobserved 'error' as a process crash. The MCP
    // connection tool reports failures through its rejected promise instead.
    if (this.listenerCount("error") > 0) this.emit("error", error);
  }

  connect(): Promise<void> {
    if (this._connected) return Promise.resolve();
    if (this.connecting) return this.connecting;
    const operation = new Promise<void>((resolve, reject) => {
      const headers: Record<string, string> = {};
      if (this.authToken) headers.Authorization = `Bearer ${this.authToken}`;
      const socket = new WebSocket(this.url, { headers });
      this.ws = socket;
      let opened = false;
      const timeout = setTimeout(() => {
        reject(new Error(`Connection timeout to ${this.url}`));
        socket.terminate();
      }, 10000);
      socket.on("open", () => {
        clearTimeout(timeout);
        if (this.ws !== socket) { socket.close(); reject(new Error("Connection cancelled")); return; }
        opened = true;
        this._connected = true;
        this.emit("connected");
        resolve();
      });
      socket.on("message", (data) => {
        if (this.ws !== socket) return;
        try {
          const response = JSON.parse(data.toString());
          const pending = this.pendingRequests.get(response?.id);
          if (!pending) return;
          clearTimeout(pending.timer);
          this.pendingRequests.delete(response.id);
          if (typeof response.success !== "boolean") pending.reject(new Error("Invalid Android command response"));
          else pending.resolve(response as CommandResponse);
        } catch (e) { this.reportError(new Error(`Failed to parse response: ${e}`)); }
      });
      socket.on("close", (code, reason) => {
        clearTimeout(timeout);
        if (!opened) reject(new Error(`Connection closed before opening (${code})`));
        if (this.ws !== socket) return;
        this.ws = null;
        this._connected = false;
        this.rejectAllPending("Connection closed");
        this.emit("disconnected", code, reason.toString());
      });
      socket.on("error", (error) => {
        clearTimeout(timeout);
        if (!opened) reject(error);
        this.reportError(error);
      });
    });
    this.connecting = operation;
    // Handle both outcomes without creating an unobserved rejected promise.
    operation.then(() => { if (this.connecting === operation) this.connecting = null; },
      () => { if (this.connecting === operation) this.connecting = null; });
    return operation;
  }

  disconnect(): void {
    const socket = this.ws;
    this.ws = null;
    this._connected = false;
    this.rejectAllPending("Disconnected");
    if (socket?.readyState === WebSocket.CONNECTING) socket.terminate();
    else socket?.close();
  }

  async sendCommand(command: string, params?: Record<string, unknown>, timeoutMs = 30000): Promise<CommandResponse> {
    const socket = this.ws;
    if (!this._connected || !socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("Not connected to Android device");
    }
    const id = `req_${++this.requestCounter}_${Date.now()}`;
    const request: CommandRequest = { id, command, params };
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`Command '${command}' timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pendingRequests.set(id, { resolve, reject, timer });
      const fail = (error?: Error): void => {
        if (!error || !this.pendingRequests.has(id)) return;
        clearTimeout(timer);
        this.pendingRequests.delete(id);
        reject(error);
      };
      try { socket.send(JSON.stringify(request), fail); }
      catch (error) { fail(error as Error); }
    });
  }

  private rejectAllPending(reason: string): void {
    for (const pending of this.pendingRequests.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error(reason));
    }
    this.pendingRequests.clear();
  }
}
