export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export type UserStatus = 'online' | 'busy' | 'away' | 'offline';

export interface User {
  id: string;
  name: string;
  email: string;
  avatarUrl?: string;
  status: UserStatus;
  role?: 'USER' | 'ADMIN' | 'SYSTEM_ADMIN';
  createdAt: string;
}

export interface DeviceSession {
  id: string;
  userId: string;
  userName: string;
  deviceName: string;
  browser: string;
  os: string;
  ipAddress: string;
  isCurrentSession: boolean;
  isOnline: boolean;
  p2pCapable: boolean;
  lastSeen: string;
  createdAt: string;
}

export interface Workspace {
  id: string;
  name: string;
  description?: string;
  icon?: string;
  ownerId: string;
  role: WorkspaceRole;
  membersCount: number;
  channelsCount: number;
  filesCount: number;
  createdAt: string;
}

export interface WorkspaceMember {
  id: string;
  workspaceId: string;
  userId: string;
  name: string;
  email: string;
  avatarUrl?: string;
  role: WorkspaceRole;
  status: UserStatus;
  activeSessions: DeviceSession[];
  joinedAt: string;
}

export interface Channel {
  id: string;
  workspaceId: string;
  name: string;
  topic?: string;
  isPrivate: boolean;
  createdAt: string;
}

export interface MessageAttachment {
  id: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  downloadUrl?: string;
  p2pAvailable?: boolean;
  ownerName?: string;
  sha256?: string;
}

export interface MessageReaction {
  emoji: string;
  count: number;
  users: string[]; // user IDs
}

export interface Message {
  id: string;
  channelId: string;
  senderId: string;
  senderName: string;
  senderAvatar?: string;
  content: string;
  createdAt: string;
  updatedAt?: string;
  isEdited?: boolean;
  isDeleted?: boolean;
  replyToId?: string;
  replyTo?: {
    id: string;
    senderName: string;
    content: string;
  };
  attachments?: MessageAttachment[];
  reactions?: MessageReaction[];
}

export type FileAvailability = 'local' | 'peer' | 'offline';

export interface FileItem {
  id: string;
  workspaceId: string;
  folderId?: string | null;
  name: string;
  type: string; // extension or mime
  size: number;
  ownerId: string;
  ownerName: string;
  folderPath?: string;
  availability: FileAvailability;
  availableFromDevice?: string; // e.g. "Ken-Laptop"
  sha256: string;
  createdAt: string;
  modifiedAt: string;
}

export interface FolderItem {
  id: string;
  workspaceId: string;
  parentId?: string | null;
  name: string;
  filesCount: number;
  createdAt: string;
  modifiedAt: string;
}

export type TransferStatus =
  | 'waiting'
  | 'connecting'
  | 'transferring'
  | 'verifying'
  | 'completed'
  | 'declined'
  | 'cancelled'
  | 'failed'
  | 'integrity_failed';

export interface FileTransfer {
  id: string;
  workspaceId?: string;
  fileId?: string;
  fileName: string;
  fileSize: number;
  fileType?: string;
  sender: {
    userId: string;
    userName: string;
    sessionId?: string;
    sessionName: string; // e.g. "Anh (Chrome on Windows)"
  };
  receiver: {
    userId: string;
    userName: string;
    sessionId?: string;
    sessionName: string; // e.g. "Ken (Chrome on Windows)"
  };
  mode: 'Direct P2P (WebRTC)' | 'LAN Direct';
  status: TransferStatus;
  transferredBytes: number;
  speedBytesPerSec: number;
  progressPercent: number;
  etaSeconds: number;
  sha256?: string;
  errorMessage?: string;
  createdAt: string;
  completedAt?: string;
}

export interface WebRTCSignalingPayload {
  transferId: string;
  fromConnectionId?: string;
  toConnectionId?: string;
  type: 'offer' | 'answer' | 'candidate';
  sdp?: { type: 'offer' | 'answer'; sdp: string };
  candidate?: { candidate: string; sdpMid?: string | null; sdpMLineIndex?: number | null };
}

export interface TransferMetadataHeader {
  type: 'header';
  transferId: string;
  fileName: string;
  fileSize: number;
  fileType: string;
  totalChunks: number;
  sha256?: string;
}

export interface ServerConnectionInfo {
  serverUrl: string;
  serverName: string;
  status: 'connected' | 'connecting' | 'disconnected' | 'reconnecting' | 'error';
  p2pStatus: 'available' | 'unavailable' | 'negotiating';
  latencyMs?: number;
  version: string;
  protocol: string;
}

// Admin Specific Types
export interface AdminOverviewStats {
  serverStatus: 'healthy' | 'degraded' | 'offline';
  connectedClients: number;
  registeredUsers: number;
  activeSessions: number;
  activeTransfers: number;
  totalWorkspaces: number;
  uptime: string;
  serverName: string;
  bindAddress: string;
  lanIp: string;
  port: number;
  wsPort: number;
  protocolVersion: string;
  interfaces: {
    name: string;
    ip: string;
    type: 'wifi' | 'ethernet' | 'loopback';
  }[];
}

export interface AdminUser {
  id: string;
  name: string;
  email: string;
  status: 'active' | 'disabled';
  sessionsCount: number;
  devicesCount: number;
  workspacesCount: number;
  role: 'USER' | 'ADMIN' | 'SYSTEM_ADMIN';
  createdAt: string;
  lastLogin: string;
}

export interface AdminClientSession {
  id: string;
  connectionId: string;
  userId?: string;
  userName?: string;
  browser: string;
  os: string;
  ipAddress: string;
  protocol: string;
  browserVersion: string;
  lastHeartbeat: string;
  status: 'online' | 'idle' | 'unauthenticated';
  connectedAt: string;
}

export interface AdminUserSession {
  id: string;
  userId: string;
  userName: string;
  deviceInfo: string;
  ipAddress: string;
  createdAt: string;
  lastSeen: string;
  expiresAt: string;
  status: 'active' | 'revoked' | 'expired';
}

export interface AdminWorkspaceItem {
  id: string;
  name: string;
  ownerName: string;
  membersCount: number;
  channelsCount: number;
  filesCount: number;
  createdAt: string;
  status: 'active' | 'archived';
}

export interface AdminTransferItem {
  id: string;
  fileName: string;
  fileSize: number;
  senderName: string;
  receiverName: string;
  senderSession: string;
  receiverSession: string;
  mode: string;
  progressPercent: number;
  status: TransferStatus;
  startedAt: string;
}

export interface AdminServerConfig {
  serverName: string;
  serverVersion: string;
  environment: string;
  protocolVersion: string;
  bindAddress: string;
  port: number;
  wsPort: number;
  heartbeatIntervalMs: number;
  heartbeatTimeoutMs: number;
  logLevel: 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
  restartRequired: boolean;
}

export interface AdminLogEntry {
  id: string;
  timestamp: string;
  level: 'INFO' | 'WARN' | 'ERROR' | 'DEBUG';
  event:
    | 'SERVER_STARTED'
    | 'CLIENT_CONNECTED'
    | 'USER_LOGIN'
    | 'SESSION_REVOKED'
    | 'TRANSFER_REQUESTED'
    | 'TRANSFER_COMPLETED'
    | 'TRANSFER_FAILED'
    | 'CLIENT_DISCONNECTED'
    | 'CONFIG_UPDATED';
  actor: string;
  target: string;
  description: string;
}
