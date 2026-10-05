'use client';

import React from 'react';
import { cn } from '@/lib/utils';
import { ServerConnectionInfo } from '@/lib/types';
import { Wifi, WifiOff, RefreshCw, Zap } from 'lucide-react';

export function ConnectionStatusBadge({
  serverInfo,
  className,
}: {
  serverInfo: ServerConnectionInfo;
  className?: string;
}) {
  const isConnected = serverInfo.status === 'connected';
  const isConnecting = serverInfo.status === 'connecting' || serverInfo.status === 'reconnecting';
  const isP2pReady = serverInfo.p2pStatus === 'available';

  return (
    <div
      className={cn(
        'inline-flex items-center gap-2 px-2.5 py-1 rounded-md text-xs border font-medium transition-colors',
        isConnected
          ? 'bg-slate-50 border-slate-200 text-slate-700'
          : isConnecting
          ? 'bg-amber-50 border-amber-200 text-amber-800'
          : 'bg-red-50 border-red-200 text-red-800',
        className
      )}
      title={`Server: ${serverInfo.serverUrl} (${serverInfo.serverName}) - Protocol: ${serverInfo.protocol}`}
    >
      {/* Server Dot & Status */}
      <span className="flex items-center gap-1.5">
        <span
          className={cn(
            'w-2 h-2 rounded-full',
            isConnected
              ? 'bg-emerald-500 ring-2 ring-emerald-200'
              : isConnecting
              ? 'bg-amber-500 animate-pulse'
              : 'bg-red-500'
          )}
        />
        <span className="truncate max-w-[120px]">
          {isConnecting ? 'Reconnecting...' : isConnected ? serverInfo.serverName : 'Server unavailable'}
        </span>
      </span>

      {/* Latency & P2P status */}
      {isConnected && (
        <>
          <span className="text-slate-300">•</span>
          <span
            className={cn(
              'inline-flex items-center gap-0.5 text-[11px]',
              isP2pReady ? 'text-teal-700 font-semibold' : 'text-slate-400'
            )}
            title={isP2pReady ? 'WebRTC DataChannel P2P available' : 'P2P unavailable - checking STUN/LAN'}
          >
            <Zap className="w-3 h-3 text-teal-600" />
            <span>P2P</span>
          </span>
          {serverInfo.latencyMs !== undefined && (
            <span className="text-[10px] text-slate-400 font-mono">
              {serverInfo.latencyMs}ms
            </span>
          )}
        </>
      )}
    </div>
  );
}
