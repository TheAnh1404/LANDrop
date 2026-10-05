'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Badge } from '@/components/ui/badge';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { SearchInput } from '@/components/ui/input';
import { MOCK_ADMIN_LOGS } from '@/lib/mock-data';
import { AdminLogEntry } from '@/lib/types';
import { FileText, Filter } from 'lucide-react';

export default function AdminLogsPage() {
  const [logs] = useState<AdminLogEntry[]>(MOCK_ADMIN_LOGS);
  const [levelFilter, setLevelFilter] = useState<string>('ALL');
  const [searchQuery, setSearchQuery] = useState('');

  const filteredLogs = logs.filter((log) => {
    if (levelFilter !== 'ALL' && log.level !== levelFilter) return false;
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      return (
        log.event.toLowerCase().includes(q) ||
        log.actor.toLowerCase().includes(q) ||
        log.target.toLowerCase().includes(q) ||
        log.description.toLowerCase().includes(q)
      );
    }
    return true;
  });

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="Audit & Server Logs"
        subtitle="Security events, WebSocket connections, session lifecycle, and P2P transfers"
      />

      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {/* Filters */}
        <div className="max-w-6xl mx-auto flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <span className="text-xs text-slate-500 font-medium">Level:</span>
            {['ALL', 'INFO', 'WARN', 'ERROR'].map((lvl) => (
              <button
                key={lvl}
                type="button"
                onClick={() => setLevelFilter(lvl)}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${
                  levelFilter === lvl
                    ? 'bg-slate-900 text-white'
                    : 'bg-slate-100 hover:bg-slate-200 text-slate-600'
                }`}
              >
                {lvl}
              </button>
            ))}
          </div>

          <div className="w-full sm:w-72">
            <SearchInput
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Filter logs by event or actor..."
            />
          </div>
        </div>

        {/* Logs Table */}
        <div className="max-w-6xl mx-auto border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[16%]">Timestamp</TableHead>
                <TableHead className="w-[8%]">Level</TableHead>
                <TableHead className="w-[18%]">Event</TableHead>
                <TableHead className="w-[16%]">Actor</TableHead>
                <TableHead className="w-[18%]">Target</TableHead>
                <TableHead className="w-[24%]">Description</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredLogs.map((log) => (
                <TableRow key={log.id}>
                  <TableCell className="font-mono text-slate-500 text-xs">
                    {log.timestamp}
                  </TableCell>

                  <TableCell>
                    <Badge
                      variant={
                        log.level === 'WARN'
                          ? 'warning'
                          : log.level === 'ERROR'
                          ? 'danger'
                          : 'neutral'
                      }
                      size="sm"
                    >
                      {log.level}
                    </Badge>
                  </TableCell>

                  <TableCell>
                    <span className="font-mono font-semibold text-slate-800 text-xs">
                      {log.event}
                    </span>
                  </TableCell>

                  <TableCell className="text-xs text-slate-700 font-medium">
                    {log.actor}
                  </TableCell>

                  <TableCell className="text-xs text-slate-600 font-mono truncate max-w-[180px]" title={log.target}>
                    {log.target}
                  </TableCell>

                  <TableCell className="text-xs text-slate-600 leading-relaxed">
                    {log.description}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </div>
    </div>
  );
}
