'use client';

import React, { createContext, useContext, useState, useEffect } from 'react';
import { User, DeviceSession, ServerConnectionInfo } from '../types';
import { CURRENT_USER, CURRENT_DEVICE_SESSION } from '../mock-data';

interface AuthContextType {
  user: User | null;
  currentSession: DeviceSession | null;
  serverInfo: ServerConnectionInfo;
  isAuthenticated: boolean;
  recentServers: string[];
  login: (email: string, password: string) => Promise<boolean>;
  register: (name: string, email: string, password: string) => Promise<boolean>;
  logout: () => void;
  connectServer: (url: string) => Promise<boolean>;
  updateProfile: (name: string, email: string) => void;
  revokeSession: (sessionId: string) => void;
}

const DEFAULT_SERVER: ServerConnectionInfo = {
  serverUrl: '192.168.1.10:3000',
  serverName: 'LANDrop Office Server',
  status: 'connected',
  p2pStatus: 'available',
  latencyMs: 4,
  version: '2.0.0-rc1',
  protocol: 'v2.0 (WebRTC + WS)',
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(CURRENT_USER);
  const [currentSession, setCurrentSession] = useState<DeviceSession | null>(CURRENT_DEVICE_SESSION);
  const [serverInfo, setServerInfo] = useState<ServerConnectionInfo>(DEFAULT_SERVER);
  const [recentServers, setRecentServers] = useState<string[]>([
    '192.168.1.10:3000',
    'localhost:3000',
    'office-lan.local:3000',
  ]);

  useEffect(() => {
    // Check local storage for persistent browser session
    if (typeof window !== 'undefined') {
      const savedUser = localStorage.getItem('landrop_auth_user');
      const savedServer = localStorage.getItem('landrop_server_url');
      if (savedUser) {
        try {
          setUser(JSON.parse(savedUser));
        } catch {
          // ignore parsing error
        }
      }
      if (savedServer) {
        setServerInfo((prev) => ({
          ...prev,
          serverUrl: savedServer,
        }));
      }
    }
  }, []);

  const login = async (email: string, _password: string): Promise<boolean> => {
    // Simulate network delay
    await new Promise((r) => setTimeout(r, 400));
    const loggedUser: User = {
      ...CURRENT_USER,
      email,
      name: email.split('@')[0] || 'The Anh',
    };
    setUser(loggedUser);
    if (typeof window !== 'undefined') {
      localStorage.setItem('landrop_auth_user', JSON.stringify(loggedUser));
    }
    return true;
  };

  const register = async (name: string, email: string, _password: string): Promise<boolean> => {
    await new Promise((r) => setTimeout(r, 400));
    const newUser: User = {
      id: `usr_${Date.now().toString(36)}`,
      name,
      email,
      status: 'online',
      role: 'USER',
      createdAt: new Date().toISOString(),
    };
    setUser(newUser);
    if (typeof window !== 'undefined') {
      localStorage.setItem('landrop_auth_user', JSON.stringify(newUser));
    }
    return true;
  };

  const logout = () => {
    setUser(null);
    if (typeof window !== 'undefined') {
      localStorage.removeItem('landrop_auth_user');
    }
  };

  const connectServer = async (url: string): Promise<boolean> => {
    setServerInfo((prev) => ({ ...prev, status: 'connecting' }));
    await new Promise((r) => setTimeout(r, 600));

    const cleanUrl = url.trim();
    if (!cleanUrl || cleanUrl.includes('invalid')) {
      setServerInfo((prev) => ({ ...prev, status: 'error', p2pStatus: 'unavailable' }));
      return false;
    }

    setServerInfo({
      serverUrl: cleanUrl,
      serverName: cleanUrl.includes('localhost') ? 'LANDrop Localhost' : 'LANDrop Office Server',
      status: 'connected',
      p2pStatus: 'available',
      latencyMs: Math.floor(Math.random() * 8) + 2,
      version: '2.0.0-rc1',
      protocol: 'v2.0 (WebRTC + WS)',
    });

    if (typeof window !== 'undefined') {
      localStorage.setItem('landrop_server_url', cleanUrl);
    }

    setRecentServers((prev) => {
      const filtered = prev.filter((s) => s !== cleanUrl);
      return [cleanUrl, ...filtered].slice(0, 5);
    });

    return true;
  };

  const updateProfile = (name: string, email: string) => {
    if (!user) return;
    const updated: User = { ...user, name, email };
    setUser(updated);
    if (typeof window !== 'undefined') {
      localStorage.setItem('landrop_auth_user', JSON.stringify(updated));
    }
  };

  const revokeSession = (sessionId: string) => {
    if (currentSession && currentSession.id === sessionId) {
      logout();
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        currentSession,
        serverInfo,
        isAuthenticated: !!user,
        recentServers,
        login,
        register,
        logout,
        connectServer,
        updateProfile,
        revokeSession,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
