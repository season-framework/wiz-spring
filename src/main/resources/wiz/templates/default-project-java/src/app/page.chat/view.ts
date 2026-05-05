import { OnDestroy, OnInit } from '@angular/core';
import { Service } from '@wiz/libs/portal/season/service';

export class Component implements OnInit, OnDestroy {
    public readonly room = 'lobby';
    public status = 'connecting';
    public messageText = '';
    public messages: any[] = [];
    private socket?: any;

    constructor(public service: Service) { }

    public async ngOnInit() {
        await this.service.init();
        await this.service.auth.allow('/access');
        this.connect();
    }

    public ngOnDestroy() {
        if (this.socket) {
            this.socket.disconnect();
        }
    }

    public send() {
        const text = this.messageText.trim();
        if (!text || !this.socket || !this.socket.connected) {
            return;
        }
        this.socket.emit('send', {
            room: this.room,
            text,
            name: this.displayName()
        });
        this.messageText = '';
        void this.service.render();
    }

    public trackByIndex(index: number) {
        return index;
    }

    public statusClass() {
        if (this.status === 'connected') {
            return 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-600/20 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-400/20';
        }
        if (this.status === 'connecting') {
            return 'bg-amber-50 text-amber-700 ring-1 ring-amber-600/20 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-400/20';
        }
        return 'bg-gray-100 text-gray-600 ring-1 ring-gray-300 dark:bg-zinc-800 dark:text-zinc-300 dark:ring-zinc-700';
    }

    public messageClass(message: any) {
        if (message.kind === 'system') {
            return 'mx-auto max-w-[80%] rounded-lg bg-gray-100 px-3 py-1.5 text-center text-[12px] text-gray-500 dark:bg-zinc-800 dark:text-zinc-400';
        }
        if (message.name === this.displayName()) {
            return 'ml-auto max-w-[82%] rounded-lg bg-indigo-600 px-3 py-2 text-white shadow-sm';
        }
        return 'mr-auto max-w-[82%] rounded-lg border border-gray-200 bg-white px-3 py-2 text-gray-900 shadow-sm dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100';
    }

    public displayTime(message: any) {
        if (!message.sentAt) return '';
        try {
            return new Date(message.sentAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch (error) {
            return '';
        }
    }

    private connect() {
        this.status = 'connecting';
        const socket = wiz.socket();
        this.socket = socket;

        socket.on('connect', () => {
            this.status = 'connected';
            socket.emit('join', { room: this.room });
            this.addSystem('chat.connected');
        });

        socket.on('join', () => {
            this.addSystem('chat.joined');
        });
        socket.on('chat.message', (message: any) => {
            this.addMessage(message);
        });
        socket.on('disconnect', () => {
            this.status = 'disconnected';
            this.addSystem('chat.disconnected');
        });
        socket.on('error', () => {
            this.status = 'disconnected';
            this.addSystem('chat.error');
        });
    }

    private addMessage(message: any) {
        try {
            this.messages.push(typeof message === 'string' ? JSON.parse(message) : message);
        } catch (error) {
            this.addSystem('chat.error');
        }
        void this.service.render();
    }

    private addSystem(key: string) {
        const label = this.systemLabel(key);
        const last = this.messages[this.messages.length - 1];
        if (last && last.kind === 'system' && last.text === label) {
            return;
        }
        this.messages.push({ kind: 'system', text: label });
        void this.service.render();
    }

    private displayName() {
        return this.service.auth.session?.name || this.service.auth.session?.email || 'Guest';
    }

    private systemLabel(key: string) {
        const ko: any = {
            'chat.connected': '연결되었습니다.',
            'chat.joined': '채팅방에 입장했습니다.',
            'chat.disconnected': '연결이 종료되었습니다.',
            'chat.error': '연결 오류가 발생했습니다.'
        };
        const en: any = {
            'chat.connected': 'Connected.',
            'chat.joined': 'Joined the chat room.',
            'chat.disconnected': 'Disconnected.',
            'chat.error': 'Connection error.'
        };
        return (this.service.lang && this.service.lang.get() === 'ko' ? ko : en)[key] || key;
    }
}
