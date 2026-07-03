type WizOptions = string | {
    baseuri?: string;
    baseUri?: string;
    apiPrefix?: string;
};

type SocketListener = (...args: any[]) => void;

class WizWebSocketClient {
    public connected = false;
    private socket?: WebSocket;
    private readonly listeners = new Map<string, SocketListener[]>();
    private readonly pending: Array<{ event: string; data: any }> = [];
    private closedByClient = false;

    constructor(private readonly uri: string) {
        this.connect();
    }

    public on(event: string, listener: SocketListener) {
        const listeners = this.listeners.get(event) || [];
        listeners.push(listener);
        this.listeners.set(event, listeners);
        return this;
    }

    public emit(event: string, data: any = {}) {
        if (!this.socket || this.socket.readyState !== WebSocket.OPEN || !this.connected) {
            if (!this.closedByClient) {
                this.pending.push({ event, data });
            }
            return;
        }
        this.send({ event, data });
    }

    public disconnect() {
        this.closedByClient = true;
        this.connected = false;
        this.pending.length = 0;
        if (this.socket && this.socket.readyState <= WebSocket.OPEN) {
            this.socket.close();
        }
    }

    private connect() {
        this.socket = new WebSocket(this.uri);
        this.socket.onmessage = (event) => this.handleMessage(event.data);
        this.socket.onerror = () => this.emitLocal("error");
        this.socket.onclose = () => {
            const notify = this.connected && !this.closedByClient;
            this.connected = false;
            if (notify) {
                this.emitLocal("disconnect");
            }
        };
    }

    private handleMessage(raw: any) {
        let envelope: any;
        try {
            envelope = JSON.parse(String(raw));
        } catch (error) {
            this.emitLocal("error");
            return;
        }
        if (envelope.event === "connect") {
            if (envelope.accepted === false) {
                this.emitLocal("error", envelope.message);
                this.disconnect();
                return;
            }
            this.connected = true;
            this.emitLocal("connect");
            this.flushPending();
            return;
        }
        if (envelope.accepted === false) {
            this.emitLocal("error", envelope.message);
            return;
        }
        this.emitLocal(envelope.event, this.payload(envelope.message));
    }

    private payload(message: any) {
        if (typeof message !== "string") {
            return message;
        }
        const trimmed = message.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return message;
        }
        try {
            return JSON.parse(trimmed);
        } catch (error) {
            return message;
        }
    }

    private emitLocal(event: string, ...args: any[]) {
        for (const listener of this.listeners.get(event) || []) {
            listener(...args);
        }
    }

    private flushPending() {
        while (this.connected && this.socket && this.socket.readyState === WebSocket.OPEN && this.pending.length > 0) {
            this.send(this.pending.shift()!);
        }
    }

    private send(message: { event: string; data: any }) {
        this.socket?.send(JSON.stringify(message));
    }
}

export default class Wiz {
    public namespace: any;
    public baseuri: string;
    public apiPrefix: string;

    constructor(options: WizOptions = "/wiz") {
        const config = this.runtimeConfig();
        if (typeof options === "string") {
            this.baseuri = this.normalizeBaseuri(options || config.baseuri || "/wiz");
            this.apiPrefix = this.normalizeApiPrefix(config.apiPrefix || "/wiz/api");
            return;
        }
        this.baseuri = this.normalizeBaseuri(options?.baseuri || options?.baseUri || config.baseuri || "/wiz");
        this.apiPrefix = this.normalizeApiPrefix(options?.apiPrefix || config.apiPrefix || "/wiz/api");
    }

    public app(namespace: any) {
        let instance = new Wiz({ baseuri: this.baseuri, apiPrefix: this.apiPrefix });
        instance.namespace = namespace;
        return instance;
    }

    private runtimeConfig() {
        if (typeof window === "undefined") return {};
        return (window as any).__WIZ_CONFIG__ || {};
    }

    private normalizeBaseuri(value: any) {
        let uri = String(value || "").trim();
        while (uri.length > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length - 1);
        }
        return uri;
    }

    private normalizeApiPrefix(value: any) {
        let prefix = String(value || "/wiz/api").trim();
        if (!prefix) prefix = "/wiz/api";
        while (prefix.length > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length - 1);
        }
        return prefix;
    }

    private cookie(name: string) {
        const cookies: Array<string> = document.cookie.split(';');
        const cookieName = `${name}=`;

        for (let index: number = 0; index < cookies.length; index += 1) {
            const cookie: string = cookies[index].replace(/^\s+/g, '');
            if (cookie.indexOf(cookieName) == 0) {
                return cookie.substring(cookieName.length, cookie.length);
            }
        }
        return '';
    }

    public dev() {
        let isdev = this.cookie("season-wiz-devmode");
        if (isdev == 'true') return true;
        return false;
    }

    public project() {
        let project = this.cookie("season-wiz-project");
        if (project) return project;
        return "main";
    }

    public socket() {
        let socketns = this.baseuri + "/ws/app/" + this.project();
        if (this.namespace)
            socketns = socketns + "/" + this.namespace;
        return new WizWebSocketClient(this.websocketUri(socketns));
    };

    private websocketUri(uri: string) {
        if (uri.startsWith("ws://") || uri.startsWith("wss://")) {
            return uri;
        }
        if (uri.startsWith("https://")) {
            return "wss://" + uri.substring("https://".length);
        }
        if (uri.startsWith("http://")) {
            return "ws://" + uri.substring("http://".length);
        }
        if (typeof window === "undefined") {
            return uri;
        }
        const protocol = window.location.protocol === "https:" ? "wss://" : "ws://";
        const path = uri.startsWith("/") ? uri : "/" + uri;
        return protocol + window.location.host + path;
    }

    public url(function_name: string) {
        if (function_name[0] == "/") function_name = function_name.substring(1);
        return this.apiPrefix + "/" + this.namespace + "/" + function_name;
    }

    private async parseResponse(response: Response) {
        try {
            return await response.clone().json();
        } catch (error) {
            const data = await response.text();
            if (response.status !== 200) {
                return { code: response.status, data: data || response.statusText };
            }
            return { code: response.status, data };
        }
    }

    public async call(function_name: string, body: any = {}, options: RequestInit = {}) {
        const uri = this.url(function_name);

        try {
            if (body) {
                const headers = new Headers(options.headers || {});
                const requestOptions: RequestInit = {
                    ...options,
                    method: options.method || "POST"
                };

                if (body instanceof FormData || body instanceof URLSearchParams || body instanceof Blob) {
                    requestOptions.body = body;
                } else {
                    if (!headers.has('Content-Type')) {
                        headers.set('Content-Type', 'application/json');
                    }
                    requestOptions.body = JSON.stringify(body);
                }

                requestOptions.headers = headers;
                const response = await fetch(uri, requestOptions);
                return await this.parseResponse(response);
            }

            const response = await fetch(uri, options);
            return await this.parseResponse(response);
        } catch (error) {
            return { code: 500, data: error };
        }
    }
}
