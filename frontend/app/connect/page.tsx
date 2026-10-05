'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { StatusDot } from '@/components/ui/badge';
import { Server, ArrowRight, CheckCircle2, AlertCircle, History, Zap } from 'lucide-react';

export default function ConnectPage() {
  const router = useRouter();
  const { serverInfo, connectServer, recentServers } = useAuth();
  const [address, setAddress] = useState(serverInfo.serverUrl || '192.168.1.10:3000');
  const [status, setStatus] = useState<'idle' | 'connecting' | 'connected' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState('');

  const handleConnect = async (targetAddress?: string) => {
    const toConnect = targetAddress || address;
    setStatus('connecting');
    setErrorMessage('');

    const success = await connectServer(toConnect);
    if (success) {
      setStatus('connected');
      setTimeout(() => {
        router.push('/auth/login');
      }, 600);
    } else {
      setStatus('error');
      setErrorMessage('Server unavailable or protocol incompatible at ' + toConnect);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col justify-center items-center p-4">
      {/* Brand Logo */}
      <div className="flex items-center gap-3 mb-8">
        <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center font-bold text-white text-base shadow-sm">
          L
        </div>
        <div>
          <div className="text-lg font-bold text-slate-900 tracking-tight">LANDrop</div>
          <div className="text-xs text-slate-500 font-medium">Browser Collaborative Workspace</div>
        </div>
      </div>

      <Card className="w-full max-w-md shadow-card">
        <CardHeader>
          <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center text-indigo-600 mb-1">
            <Server className="w-5 h-5" />
          </div>
          <CardTitle>Connect to LANDrop Server</CardTitle>
          <CardDescription>
            Enter your local LAN or corporate server address to start collaborating
          </CardDescription>
        </CardHeader>

        <CardContent className="space-y-4">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              handleConnect();
            }}
            className="space-y-3"
          >
            <Input
              label="Server Address"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="192.168.1.10:3000 or localhost:3000"
              required
              disabled={status === 'connecting'}
              error={errorMessage}
            />

            <Button
              type="submit"
              variant="primary"
              size="md"
              className="w-full"
              isLoading={status === 'connecting'}
              disabled={!address.trim()}
              rightIcon={<ArrowRight className="w-4 h-4" />}
            >
              {status === 'connecting'
                ? 'Connecting...'
                : status === 'connected'
                ? 'Connected!'
                : 'Connect'}
            </Button>
          </form>

          {/* Connection Status indicator */}
          {status === 'connected' && (
            <div className="flex items-center gap-2 p-3 bg-emerald-50 text-emerald-800 border border-emerald-200 rounded-lg text-xs font-medium animate-in fade-in">
              <CheckCircle2 className="w-4 h-4 text-emerald-600 flex-shrink-0" />
              <span>Connected to {serverInfo.serverName} (v{serverInfo.version})</span>
            </div>
          )}

          {/* Recent Servers */}
          {recentServers && recentServers.length > 0 && (
            <div className="pt-2 border-t border-slate-100">
              <div className="flex items-center gap-1 text-[11px] font-semibold text-slate-400 uppercase tracking-wider mb-2">
                <History className="w-3 h-3" />
                <span>Recent Servers</span>
              </div>
              <div className="space-y-1">
                {recentServers.map((srv) => (
                  <button
                    key={srv}
                    type="button"
                    onClick={() => {
                      setAddress(srv);
                      handleConnect(srv);
                    }}
                    className="w-full flex items-center justify-between p-2 rounded-lg text-xs text-slate-700 hover:bg-slate-100 transition-colors text-left"
                  >
                    <span className="font-mono">{srv}</span>
                    <span className="text-[10px] text-slate-400">LAN</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          <div className="p-3 bg-slate-50 border border-slate-200/80 rounded-lg flex items-center gap-2 text-xs text-slate-500">
            <Zap className="w-4 h-4 text-teal-600 flex-shrink-0" />
            <span>Direct P2P file transfers are negotiated via WebRTC after connecting</span>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
