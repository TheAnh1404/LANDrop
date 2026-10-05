'use client';

import React, { useState } from 'react';
import { Modal } from '../ui/modal';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Avatar } from '../ui/avatar';
import { Badge } from '../ui/badge';
import { StatusDot } from '../ui/badge';
import { Tabs } from '../ui/file-icon';
import { useAuth } from '@/lib/auth/auth-context';
import { MOCK_SESSIONS } from '@/lib/mock-data';
import { User, Laptop, Server, LogOut, CheckCircle2, Shield } from 'lucide-react';

export function UserSettingsModal({
  isOpen,
  onClose,
}: {
  isOpen: boolean;
  onClose: () => void;
}) {
  const { user, currentSession, serverInfo, updateProfile, revokeSession, logout } = useAuth();
  const [activeTab, setActiveTab] = useState('profile');

  // Form states
  const [name, setName] = useState(user?.name || '');
  const [email, setEmail] = useState(user?.email || '');
  const [isSaved, setIsSaved] = useState(false);

  const tabs = [
    { id: 'profile', label: 'Profile', icon: <User className="w-3.5 h-3.5" /> },
    { id: 'sessions', label: 'Browser Sessions', icon: <Laptop className="w-3.5 h-3.5" /> },
    { id: 'server', label: 'Server Connection', icon: <Server className="w-3.5 h-3.5" /> },
  ];

  const handleSaveProfile = (e: React.FormEvent) => {
    e.preventDefault();
    updateProfile(name, email);
    setIsSaved(true);
    setTimeout(() => setIsSaved(false), 2000);
  };

  const userSessions = MOCK_SESSIONS.filter((s) => s.userId === user?.id);

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="User & Browser Settings"
      description="Manage your identity, browser sessions, and server connection"
      size="lg"
    >
      <div className="space-y-4">
        {/* Navigation Tabs */}
        <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />

        {/* Tab 1: Profile */}
        {activeTab === 'profile' && (
          <form onSubmit={handleSaveProfile} className="space-y-4 pt-2">
            <div className="flex items-center gap-4 p-4 bg-slate-50 border border-slate-200/80 rounded-xl">
              <Avatar name={user?.name || 'User'} size="lg" status={user?.status || 'online'} />
              <div>
                <div className="text-sm font-semibold text-slate-900">{user?.name}</div>
                <div className="text-xs text-slate-500">{user?.email}</div>
                <div className="mt-1">
                  <Badge variant="default" size="sm">
                    {user?.role || 'USER'}
                  </Badge>
                </div>
              </div>
            </div>

            <div className="space-y-3">
              <Input
                label="Display Name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
              <Input
                label="Email Address"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
              />
            </div>

            <div className="flex items-center justify-between pt-2">
              <Button
                variant="ghost"
                size="sm"
                type="button"
                onClick={logout}
                className="text-red-600 hover:text-red-700 hover:bg-red-50"
                leftIcon={<LogOut className="w-3.5 h-3.5" />}
              >
                Sign Out
              </Button>
              <div className="flex items-center gap-2">
                {isSaved && (
                  <span className="text-xs text-emerald-600 font-medium flex items-center gap-1">
                    <CheckCircle2 className="w-3.5 h-3.5" /> Saved
                  </span>
                )}
                <Button variant="primary" size="sm" type="submit">
                  Save Changes
                </Button>
              </div>
            </div>
          </form>
        )}

        {/* Tab 2: Sessions / Devices */}
        {activeTab === 'sessions' && (
          <div className="space-y-3 pt-2">
            <p className="text-xs text-slate-500">
              Browser sessions currently enrolled and authenticated for your account. You can revoke
              older or unrecognized sessions at any time.
            </p>
            <div className="space-y-2">
              {userSessions.map((session) => (
                <div
                  key={session.id}
                  className="flex items-center justify-between p-3.5 bg-white border border-slate-200 rounded-xl"
                >
                  <div className="flex items-center gap-3">
                    <StatusDot status={session.isOnline ? 'online' : 'offline'} />
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-slate-900">
                          {session.browser} on {session.os}
                        </span>
                        {session.isCurrentSession ? (
                          <Badge variant="success" size="sm">
                            Current session
                          </Badge>
                        ) : session.isOnline ? (
                          <Badge variant="neutral" size="sm">
                            Online
                          </Badge>
                        ) : null}
                      </div>
                      <div className="text-[11px] text-slate-500 mt-0.5">
                        Device: {session.deviceName} • IP: {session.ipAddress} • Last active:{' '}
                        {session.lastSeen}
                      </div>
                    </div>
                  </div>

                  {!session.isCurrentSession && (
                    <Button
                      variant="outline"
                      size="xs"
                      onClick={() => revokeSession(session.id)}
                      className="text-red-600 hover:bg-red-50 hover:border-red-200"
                    >
                      Revoke
                    </Button>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Tab 3: Server Connection */}
        {activeTab === 'server' && (
          <div className="space-y-4 pt-2">
            <div className="p-4 bg-slate-50 border border-slate-200/80 rounded-xl space-y-2.5 text-xs">
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Server Name:</span>
                <span className="font-semibold text-slate-900">{serverInfo.serverName}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Address:</span>
                <span className="font-mono text-slate-700">{serverInfo.serverUrl}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Protocol:</span>
                <span className="text-slate-700 font-mono">{serverInfo.protocol}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">P2P Status:</span>
                <Badge variant={serverInfo.p2pStatus === 'available' ? 'p2p' : 'warning'} size="sm">
                  {serverInfo.p2pStatus === 'available' ? 'WebRTC DataChannel Ready' : 'Negotiating'}
                </Badge>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-slate-500">Connection Latency:</span>
                <span className="text-slate-700 font-mono">{serverInfo.latencyMs} ms</span>
              </div>
            </div>

            <div className="pt-2 flex justify-end">
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  onClose();
                  window.location.href = '/connect';
                }}
              >
                Change Server Connection
              </Button>
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
