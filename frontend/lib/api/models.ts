import type { Workspace, WorkspaceMember, Channel, Message, FileItem, FolderItem } from '../types';

export interface WireWorkspace { id: string; name: string; description?: string; ownerId: string; role: Workspace['role']; createdAt: string; joinPolicy?: Workspace['joinPolicy']; hasPassword?: boolean }
export interface WireMember { id: string; workspaceId: string; userId: string; role: WorkspaceMember['role']; joinedAt: string; user: { id: string; username: string; displayName?: string; avatarUrl?: string } }
export interface WireMessage { id: string; channelId: string; senderId: string; sender: { username: string; displayName?: string; avatarUrl?: string }; content: string; createdAt: string; updatedAt?: string; editedAt?: string; deletedAt?: string; replyToId?: string; clientMessageId?: string }
export interface WireFile { id: string; workspaceId: string; folderId?: string; name: string; size: number; uploaderId: string; mimeType: string; hash?: string; createdAt: string; updatedAt: string; storageType: string }
export interface WireFolder { id: string; workspaceId: string; parentId?: string; createdById?: string; name: string; createdAt: string; updatedAt: string }
export const mapWorkspace = (item: WireWorkspace): Workspace => ({ ...item, joinPolicy: item.joinPolicy || 'INVITE_ONLY', hasPassword: !!item.hasPassword, membersCount: 0, channelsCount: 0, filesCount: 0 });
export const mapMember = (item: WireMember): WorkspaceMember => ({ ...item, name: item.user.displayName || item.user.username, email: '', avatarUrl: item.user.avatarUrl, status: 'offline', activeSessions: [] });
export const mapMessage = (item: WireMessage): Message => ({ ...item, senderName: item.sender.displayName || item.sender.username, senderAvatar: item.sender.avatarUrl, isEdited: !!item.editedAt, isDeleted: !!item.deletedAt });
export const mapFile = (item: WireFile): FileItem => ({ ...item, type: item.mimeType || item.name.split('.').pop() || 'file', ownerId: item.uploaderId, ownerName: '', availability: 'offline', sha256: item.hash || '', modifiedAt: item.updatedAt });
export const mapFolder = (item: WireFolder): FolderItem => ({ ...item, filesCount: 0, modifiedAt: item.updatedAt });
export type { Channel };
