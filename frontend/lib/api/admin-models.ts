export interface AdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string;
  systemRole: 'USER' | 'SYSTEM_ADMIN';
  status: 'ACTIVE' | 'DISABLED';
  activeSessionsCount: number;
  createdAt: string;
}
export interface AdminWorkspace {
  id: string;
  name: string;
  description?: string;
  ownerId: string;
  ownerUsername: string;
  memberCount: number;
}
export interface AdminMember {
  userId: string;
  username: string;
  displayName: string;
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
  joinedAt: string;
}
export interface AdminIdentity {
  user: AdminUser;
  devices: { id: string; deviceName?: string; name?: string; platform?: string; isOnline?: boolean }[];
  sessions: { id: string; deviceName?: string; expiresAt: string; isActive: boolean; revoked: boolean }[];
  connections: { connectionId: string; deviceName?: string; remoteAddress: string }[];
}
