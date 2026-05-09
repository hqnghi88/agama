const WS_URL = 'ws://127.0.0.1:8080/simulation/events';

type EventHandler = (event: WebSocketEvent) => void;

interface WebSocketEvent {
  type: string;
  data: Record<string, unknown>;
}

type ConnectionState = 'disconnected' | 'connecting' | 'connected';

class SimulationWebSocket {
  private ws: WebSocket | null = null;
  private handlers: Map<string, Set<EventHandler>> = new Map();
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private state: ConnectionState = 'disconnected';
  private url: string;

  constructor(url: string = WS_URL) {
    this.url = url;
  }

  connect(): void {
    if (this.ws?.readyState === WebSocket.OPEN) return;

    this.state = 'connecting';
    this.notifyHandlers('connection', {state: 'connecting'});

    try {
      this.ws = new WebSocket(this.url);

      this.ws.onopen = () => {
        this.state = 'connected';
        this.notifyHandlers('connection', {state: 'connected'});
      };

      this.ws.onmessage = (event: MessageEvent) => {
        try {
          const parsed: WebSocketEvent = JSON.parse(event.data as string);
          this.notifyHandlers(parsed.type, parsed.data);
          this.notifyHandlers('*', parsed);
        } catch {
          // Binary data or unparseable
        }
      };

      this.ws.onclose = () => {
        this.state = 'disconnected';
        this.notifyHandlers('connection', {state: 'disconnected'});
        this.scheduleReconnect();
      };

      this.ws.onerror = () => {
        this.state = 'disconnected';
        this.notifyHandlers('connection', {state: 'error'});
      };
    } catch {
      this.state = 'disconnected';
      this.scheduleReconnect();
    }
  }

  disconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.state = 'disconnected';
  }

  on(eventType: string, handler: EventHandler): () => void {
    if (!this.handlers.has(eventType)) {
      this.handlers.set(eventType, new Set());
    }
    this.handlers.get(eventType)!.add(handler);
    return () => this.handlers.get(eventType)?.delete(handler);
  }

  getState(): ConnectionState {
    return this.state;
  }

  isConnected(): boolean {
    return this.state === 'connected';
  }

  private notifyHandlers(type: string, data: Record<string, unknown>): void {
    this.handlers.get(type)?.forEach(h => h({type, data}));
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.state !== 'connected') {
        this.connect();
      }
    }, 3000);
  }
}

const simulationWs = new SimulationWebSocket();
export {simulationWs, SimulationWebSocket};
export type {WebSocketEvent, ConnectionState, EventHandler};
