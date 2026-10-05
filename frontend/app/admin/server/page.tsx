'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs } from '@/components/ui/file-icon';
import { MOCK_SERVER_CONFIG } from '@/lib/mock-data';
import { AdminServerConfig } from '@/lib/types';
import {
  Server,
  Network,
  Clock,
  FileText,
  AlertTriangle,
  RotateCw,
  CheckCircle2,
  Wifi,
} from 'lucide-react';

export default function AdminServerSettingsPage() {
  const [config, setConfig] = useState<AdminServerConfig>(MOCK_SERVER_CONFIG);
  const [activeTab, setActiveTab] = useState('network');
  const [isRestartRequired, setIsRestartRequired] = useState(false);
  const [isRestarting, setIsRestarting] = useState(false);
  const [restartDone, setRestartDone] = useState(false);

  // Form local state
  const [port, setPort] = useState(config.port.toString());
  const [wsPort, setWsPort] = useState(config.wsPort.toString());
  const [bindAddress, setBindAddress] = useState(config.bindAddress);
  const [serverName, setServerName] = useState(config.serverName);
  const [logLevel, setLogLevel] = useState(config.logLevel);

  const tabs = [
    { id: 'network', label: 'Network', icon: <Network className="w-3.5 h-3.5" /> },
    { id: 'general', label: 'General', icon: <Server className="w-3.5 h-3.5" /> },
    { id: 'realtime', label: 'Realtime', icon: <Clock className="w-3.5 h-3.5" /> },
    { id: 'logging', label: 'Logging', icon: <FileText className="w-3.5 h-3.5" /> },
  ];

  const handleNetworkSave = (e: React.FormEvent) => {
    e.preventDefault();
    const newPort = parseInt(port, 10);
    const newWsPort = parseInt(wsPort, 10);

    setConfig((prev) => ({
      ...prev,
      port: newPort,
      wsPort: newWsPort,
      bindAddress,
      restartRequired: true,
    }));

    setIsRestartRequired(true);
  };

  const handleRestart = async () => {
    setIsRestarting(true);
    await new Promise((r) => setTimeout(r, 1200));
    setIsRestarting(false);
    setIsRestartRequired(false);
    setRestartDone(true);
    setTimeout(() => setRestartDone(false), 3000);
  };

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-slate-50">
      <AdminTopBar
        title="Server Configuration"
        subtitle="Manage network bindings, ports, heartbeat policies, and logging"
      />

      <div className="flex-1 overflow-y-auto p-6">
        <div className="max-w-4xl mx-auto space-y-6">
          {/* Prominent Restart Required Banner */}
          {isRestartRequired && (
            <div className="p-4 bg-amber-50 border border-amber-300 rounded-xl flex items-center justify-between gap-4 animate-in fade-in slide-in-from-top-2 duration-200 shadow-sm">
              <div className="flex items-center gap-3">
                <div className="w-9 h-9 rounded-lg bg-amber-100 flex items-center justify-center text-amber-700 flex-shrink-0">
                  <AlertTriangle className="w-5 h-5" />
                </div>
                <div>
                  <div className="text-xs font-bold text-amber-900">Restart Required</div>
                  <div className="text-xs text-amber-800 mt-0.5">
                    Port or network binding modifications will take effect after restarting the LANDrop server.
                  </div>
                </div>
              </div>
              <Button
                variant="primary"
                size="sm"
                className="bg-amber-600 hover:bg-amber-700 active:bg-amber-800 text-white flex-shrink-0"
                onClick={handleRestart}
                isLoading={isRestarting}
                leftIcon={<RotateCw className="w-3.5 h-3.5" />}
              >
                Restart Server Now
              </Button>
            </div>
          )}

          {restartDone && (
            <div className="p-3 bg-emerald-50 border border-emerald-200 text-emerald-800 rounded-xl text-xs font-medium flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-600" />
              <span>Server restarted successfully! New port and network bindings are active.</span>
            </div>
          )}

          {/* Navigation Tabs */}
          <div className="bg-white rounded-xl border border-slate-200/80 px-4 pt-2 shadow-subtle">
            <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} />
          </div>

          {/* Section: Network (FLOW 6) */}
          {activeTab === 'network' && (
            <Card>
              <CardHeader>
                <CardTitle>Network & Port Configuration</CardTitle>
                <CardDescription>
                  Configure TCP listening ports and LAN bind addresses
                </CardDescription>
              </CardHeader>
              <CardContent>
                <form onSubmit={handleNetworkSave} className="space-y-4">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <Input
                      label="HTTP Port"
                      type="number"
                      value={port}
                      onChange={(e) => setPort(e.target.value)}
                      helperText="Default: 3000. Changes require server restart."
                      required
                    />
                    <Input
                      label="WebSocket Realtime Port"
                      type="number"
                      value={wsPort}
                      onChange={(e) => setWsPort(e.target.value)}
                      helperText="Default: 3001 for WebRTC signaling and chat."
                      required
                    />
                  </div>

                  <Input
                    label="Bind Address"
                    value={bindAddress}
                    onChange={(e) => setBindAddress(e.target.value)}
                    helperText="Use 0.0.0.0 to listen on all local network interfaces."
                    required
                  />

                  {/* Detected interfaces list */}
                  <div className="pt-2">
                    <div className="text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
                      Detected LAN Interfaces
                    </div>
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="p-3 bg-slate-50 border border-slate-200 rounded-lg">
                        <span className="text-slate-400 block text-[10px]">Wi-Fi 6</span>
                        <span className="font-mono font-semibold text-slate-800">192.168.1.10</span>
                      </div>
                      <div className="p-3 bg-slate-50 border border-slate-200 rounded-lg">
                        <span className="text-slate-400 block text-[10px]">Ethernet</span>
                        <span className="font-mono font-semibold text-slate-800">10.0.0.5</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex justify-end pt-2">
                    <Button variant="primary" size="sm" type="submit">
                      Save Network Settings
                    </Button>
                  </div>
                </form>
              </CardContent>
            </Card>
          )}

          {/* Section: General */}
          {activeTab === 'general' && (
            <Card>
              <CardHeader>
                <CardTitle>General Server Properties</CardTitle>
                <CardDescription>
                  Identification and environment tags
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <Input
                  label="Server Name"
                  value={serverName}
                  onChange={(e) => setServerName(e.target.value)}
                  required
                />
                <div className="grid grid-cols-3 gap-3 p-3.5 bg-slate-50 border border-slate-200 rounded-xl text-xs">
                  <div>
                    <span className="text-slate-400 block text-[10px]">Server Version</span>
                    <span className="font-mono font-semibold text-slate-800">
                      {config.serverVersion}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400 block text-[10px]">Environment</span>
                    <span className="font-semibold text-slate-800">{config.environment}</span>
                  </div>
                  <div>
                    <span className="text-slate-400 block text-[10px]">Protocol</span>
                    <span className="font-mono font-semibold text-slate-800">
                      {config.protocolVersion}
                    </span>
                  </div>
                </div>
              </CardContent>
            </Card>
          )}

          {/* Section: Realtime */}
          {activeTab === 'realtime' && (
            <Card>
              <CardHeader>
                <CardTitle>Realtime & WebSocket Policy</CardTitle>
                <CardDescription>
                  Heartbeat intervals and client disconnection timeouts
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <Input
                    label="Heartbeat Interval (ms)"
                    type="number"
                    value={config.heartbeatIntervalMs}
                    disabled
                  />
                  <Input
                    label="Heartbeat Timeout (ms)"
                    type="number"
                    value={config.heartbeatTimeoutMs}
                    disabled
                  />
                </div>
              </CardContent>
            </Card>
          )}

          {/* Section: Logging */}
          {activeTab === 'logging' && (
            <Card>
              <CardHeader>
                <CardTitle>Logging & Diagnostics</CardTitle>
                <CardDescription>
                  Operational log detail and retention
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div>
                  <label className="block text-xs font-medium text-slate-700 mb-1.5">
                    Log Level
                  </label>
                  <div className="grid grid-cols-4 gap-2">
                    {(['DEBUG', 'INFO', 'WARN', 'ERROR'] as const).map((level) => (
                      <button
                        key={level}
                        type="button"
                        onClick={() => setLogLevel(level)}
                        className={`p-2.5 rounded-lg border text-xs font-mono font-semibold transition-colors ${
                          logLevel === level
                            ? 'border-indigo-600 bg-indigo-50 text-indigo-700'
                            : 'border-slate-200 hover:bg-slate-50 text-slate-600'
                        }`}
                      >
                        {level}
                      </button>
                    ))}
                  </div>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
