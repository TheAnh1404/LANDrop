export interface P2PTask {
  id: string; name: string; size: number; mime: string; hash?: string;
  direction: 'sending' | 'receiving';
  status: 'queued' | 'preparing' | 'waiting' | 'offered' | 'transferring' | 'paused' | 'interrupted' | 'verifying' | 'completed' | 'cancelled' | 'rejected' | 'failed';
  offset: number; speed?: number; verified?: boolean; disk?: boolean; error?: string; savedTo?: string; createdAt: number; updatedAt?: number;
}
export interface P2PMessage {
  id: string; text: string; senderName: string; timestamp: number; replyToId?: string; sequence?: number;
  direction: 'sent' | 'received'; type: 'text' | 'file'; ack?: boolean; pending?: boolean; status?: string;
  received?: boolean; receiveRttMs?: number; durableRttMs?: number; storageError?: string;
}
export interface ReliablePeerOptions {
  scope: string; localName?: string; remoteName?: string; workerUrl?: string; maxMessageSize?: number;
  onChat?: (message: P2PMessage) => void; onTasks?: (tasks: P2PTask[]) => void;
  onOffer?: (task: P2PTask) => void; onReady?: (capabilities: unknown) => void; onError?: (message: string) => void;
  onChatTiming?: (timing: { id: string; stage: 'received' | 'durable'; roundTripMs: number }) => void;
  onDisconnect?: (reason: string) => void;
  onReceived?: (task: P2PTask & { blobUrl: string; hash: string; savedTo: string }) => void;
}
export class ReliablePeer {
  constructor(options: ReliablePeerOptions);
  capable: boolean; initialized: Promise<void>;
  attach(control: RTCDataChannel, files?: RTCDataChannel | null, chat?: RTCDataChannel | null): void;
  setFileChannel(channel: RTCDataChannel): void;
  setChatChannel(channel: RTCDataChannel | null): void;
  receive(data: string | ArrayBuffer): boolean;
  sendChat(text: string, replyToId?: string): Promise<P2PMessage>;
  retryChat(id: string): Promise<void>; cancelChat(id: string): Promise<void>; clearChat(): Promise<void>;
  ready(): Promise<void>;
  enqueueFile(file: File, resumeId?: string): Promise<string>;
  acceptFile(id: string, destination?: FileSystemFileHandle): Promise<void>; rejectFile(id: string): Promise<void>;
  pause(id: string): Promise<void>; resume(id: string, file?: File): Promise<string>; cancel(id: string): Promise<void>;
  removeTask(id: string): Promise<void>; detach(): void; dispose(): void;
  savedFile(id: string): Promise<{ blobUrl: string; hash: string; savedTo: string }>;
}
export const MEMORY_LIMIT: number;
export const VERSION: number;
export function uuid(): string;
