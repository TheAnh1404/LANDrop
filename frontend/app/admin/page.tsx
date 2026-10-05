'use client';

import React from 'react';
import Link from 'next/link';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { StatusDot } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { MOCK_ADMIN_OVERVIEW, MOCK_ADMIN_CLIENTS, MOCK_ADMIN_LOGS } from '@/lib/mock-data';
import {
  Activity,
  Users,
  Monitor,
  KeyRound,
  ArrowLeftRight,
  Server,
  Wifi,
  Network,
  Clock,
  ChevronRight,
  ShieldCheck,
  CheckCircle2,
  HardDrive,
  Cpu,
} from 'lucide-react';

export default function AdminOverviewPage() {
  const stats = MOCK_ADMIN_OVERVIEW;
  const recentClients = MOCK_ADMIN_CLIENTS.slice(0, 4);
  const recentLogs = MOCK_ADMIN_LOGS.slice(0, 5);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-slate-50">
      <AdminTopBar
        title="Server Control Center"
        subtitle="Live operations, client WebSocket connections, and WebRTC signaling status"
      />

      <main className="flex-1 overflow-y-auto p-6 space-y-6">
        {/* Top Operational Status Banner */}
        <div className="bg-slate-900 text-white p-5 rounded-2xl shadow-panel border border-slate-800 flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 rounded-xl bg-amber-500/20 border border-amber-500/30 flex items-center justify-center text-amber-400 flex-shrink-0">
              <Server className="w-6 h-6" />
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h2 className="text-base font-bold text-white">{stats.serverName}</h2>
                <Badge variant="success" size="sm">
                  Operational
                </Badge>
              </div>
              <div className="text-xs text-slate-400 mt-0.5 flex items-center gap-3">
                <span>LAN Host: <strong className="text-slate-200 font-mono">{stats.lanIp}:{stats.port}</strong></span>
                <span>•</span>
                <span>Uptime: <strong className="text-slate-200">{stats.uptime}</strong></span>
                <span>•</span>
                <span>Version: <strong className="text-slate-200 font-mono">{stats.protocolVersion}</strong></span>
              </div>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Link href="/admin/server">
              <Button
                variant="outline"
                size="sm"
                className="bg-slate-800 border-slate-700 text-slate-200 hover:bg-slate-700 hover:text-white"
              >
                Network Settings
              </Button>
            </Link>
            <Link href="/admin/logs">
              <Button
                variant="outline"
                size="sm"
                className="bg-slate-800 border-slate-700 text-slate-200 hover:bg-slate-700 hover:text-white"
              >
                Audit Logs
              </Button>
            </Link>
          </div>
        </div>

        {/* 5-Column KPI Cards Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
          <Card>
            <CardContent className="p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  Health
                </span>
                <StatusDot status="online" />
              </div>
              <div className="text-xl font-bold text-slate-900 mt-2 capitalize flex items-center gap-1.5">
                <CheckCircle2 className="w-5 h-5 text-emerald-600" />
                <span>Healthy</span>
              </div>
              <div className="text-[11px] text-slate-400 mt-1">Uptime: {stats.uptime}</div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  Clients
                </span>
                <Monitor className="w-4 h-4 text-indigo-600" />
              </div>
              <div className="text-xl font-bold text-slate-900 mt-2">
                {stats.connectedClients}
              </div>
              <div className="text-[11px] text-slate-400 mt-1">Active browser sockets</div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  Users
                </span>
                <Users className="w-4 h-4 text-indigo-600" />
              </div>
              <div className="text-xl font-bold text-slate-900 mt-2">
                {stats.registeredUsers}
              </div>
              <div className="text-[11px] text-slate-400 mt-1">Enrolled accounts</div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  Sessions
                </span>
                <KeyRound className="w-4 h-4 text-indigo-600" />
              </div>
              <div className="text-xl font-bold text-slate-900 mt-2">
                {stats.activeSessions}
              </div>
              <div className="text-[11px] text-slate-400 mt-1">Browser devices online</div>
            </CardContent>
          </Card>

          <Card>
            <CardContent className="p-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">
                  P2P Transfers
                </span>
                <ArrowLeftRight className="w-4 h-4 text-teal-600" />
              </div>
              <div className="text-xl font-bold text-slate-900 mt-2">
                {stats.activeTransfers}
              </div>
              <div className="text-[11px] text-teal-700 font-medium mt-1">Direct WebRTC</div>
            </CardContent>
          </Card>
        </div>

        {/* Network & Infrastructure Card */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Card className="lg:col-span-2">
            <CardHeader>
              <div className="flex items-center gap-2">
                <Network className="w-4 h-4 text-indigo-600" />
                <CardTitle>Network Interfaces & Bindings</CardTitle>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 p-4 bg-slate-50 rounded-xl border border-slate-200 text-xs">
                <div>
                  <span className="text-slate-400 block text-[11px]">Server Name</span>
                  <span className="font-semibold text-slate-900">{stats.serverName}</span>
                </div>
                <div>
                  <span className="text-slate-400 block text-[11px]">Bind Address</span>
                  <span className="font-mono text-slate-900">{stats.bindAddress}</span>
                </div>
                <div>
                  <span className="text-slate-400 block text-[11px]">HTTP / WS Ports</span>
                  <span className="font-mono text-slate-900 font-semibold">
                    {stats.port} / {stats.wsPort}
                  </span>
                </div>
                <div>
                  <span className="text-slate-400 block text-[11px]">Protocol</span>
                  <span className="font-mono text-slate-900">{stats.protocolVersion}</span>
                </div>
              </div>

              {/* Detected Interfaces */}
              <div>
                <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
                  Detected LAN Interfaces
                </div>
                <div className="space-y-2">
                  {stats.interfaces.map((iface) => (
                    <div
                      key={iface.name}
                      className="flex items-center justify-between p-3 bg-white border border-slate-200 rounded-xl text-xs hover:border-slate-300 transition-colors"
                    >
                      <div className="flex items-center gap-3">
                        <div className="p-2 rounded-lg bg-indigo-50 text-indigo-600">
                          {iface.type === 'wifi' ? <Wifi className="w-4 h-4" /> : <Network className="w-4 h-4" />}
                        </div>
                        <div>
                          <div className="font-semibold text-slate-900">{iface.name}</div>
                          <div className="text-[11px] text-slate-400 capitalize">{iface.type} interface</div>
                        </div>
                      </div>
                      <div className="font-mono text-xs font-semibold text-slate-700 bg-slate-50 px-2 py-1 rounded border border-slate-200/80">
                        {iface.ip}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </CardContent>
          </Card>

          {/* Connected Browser Clients Snippet */}
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between w-full">
                <CardTitle>Connected Clients</CardTitle>
                <Link
                  href="/admin/clients"
                  className="text-xs text-indigo-600 hover:text-indigo-800 font-medium"
                >
                  View All
                </Link>
              </div>
            </CardHeader>
            <CardContent className="p-0 divide-y divide-slate-100">
              {recentClients.map((cli) => (
                <div key={cli.id} className="p-3.5 px-4 flex items-center justify-between text-xs hover:bg-slate-50/60">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <StatusDot status={cli.status === 'online' ? 'online' : 'offline'} />
                    <div className="min-w-0">
                      <div className="font-semibold text-slate-900 truncate">
                        {cli.userName || 'Unauthenticated'}
                      </div>
                      <div className="text-[10px] text-slate-400 font-mono truncate">
                        {cli.browser} • {cli.ipAddress}
                      </div>
                    </div>
                  </div>
                  <Badge variant="neutral" size="sm">
                    {cli.lastHeartbeat}
                  </Badge>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>

        {/* Recent Server Events */}
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between w-full">
              <div className="flex items-center gap-2">
                <Activity className="w-4 h-4 text-indigo-600" />
                <CardTitle>Recent Audit Trail Events</CardTitle>
              </div>
              <Link
                href="/admin/logs"
                className="text-xs text-indigo-600 hover:text-indigo-800 font-medium flex items-center gap-1"
              >
                <span>View Complete Logs</span>
                <ChevronRight className="w-3.5 h-3.5" />
              </Link>
            </div>
          </CardHeader>
          <CardContent className="p-0 divide-y divide-slate-100">
            {recentLogs.map((log) => (
              <div key={log.id} className="p-3 px-5 flex items-center justify-between text-xs hover:bg-slate-50/60">
                <div className="flex items-center gap-3 min-w-0">
                  <Badge
                    variant={log.level === 'WARN' ? 'warning' : log.level === 'ERROR' ? 'danger' : 'neutral'}
                    size="sm"
                  >
                    {log.event}
                  </Badge>
                  <span className="text-slate-700 truncate max-w-lg">{log.description}</span>
                </div>
                <span className="text-[11px] text-slate-400 font-mono whitespace-nowrap">{log.timestamp}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      </main>
    </div>
  );
}
