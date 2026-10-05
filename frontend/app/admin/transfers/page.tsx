'use client';

import React, { useState } from 'react';
import { AdminTopBar } from '@/components/layout/admin-topbar';
import { Badge } from '@/components/ui/badge';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { Progress } from '@/components/ui/transfer-progress';
import { FileIcon } from '@/components/ui/file-icon';
import { MOCK_ADMIN_TRANSFERS } from '@/lib/mock-data';
import { AdminTransferItem } from '@/lib/types';
import { formatBytes } from '@/lib/utils';
import { ArrowLeftRight, Zap, Info } from 'lucide-react';

export default function AdminTransfersPage() {
  const [transfers] = useState<AdminTransferItem[]>(MOCK_ADMIN_TRANSFERS);

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      <AdminTopBar
        title="P2P Transfer Coordination Monitor"
        subtitle="Tracking direct browser-to-browser WebRTC DataChannel connections"
      />

      <div className="flex-1 overflow-y-auto p-6 space-y-4">
        {/* Architecture Note */}
        <div className="max-w-6xl mx-auto p-3.5 bg-teal-50 border border-teal-200/80 rounded-xl flex items-center gap-3 text-xs text-teal-900">
          <Zap className="w-5 h-5 text-teal-600 flex-shrink-0" />
          <div>
            <strong>Direct WebRTC Transfer Architecture:</strong> Binary file bytes flow directly
            between the sender and receiver browser sessions. The LANDrop Server only provides
            signaling and metadata coordination, and <strong>does not relay or store file payloads</strong>.
          </div>
        </div>

        <div className="max-w-6xl mx-auto border border-slate-200 rounded-xl overflow-hidden shadow-subtle">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-[24%]">File</TableHead>
                <TableHead className="w-[14%]">Sender</TableHead>
                <TableHead className="w-[14%]">Receiver</TableHead>
                <TableHead className="w-[12%]">Size</TableHead>
                <TableHead className="w-[12%]">Mode</TableHead>
                <TableHead className="w-[12%]">Progress</TableHead>
                <TableHead className="w-[12%] text-right">Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {transfers.map((t) => (
                <TableRow key={t.id}>
                  <TableCell>
                    <div className="flex items-center gap-2.5 min-w-0">
                      <FileIcon type={t.fileName.split('.').pop()} />
                      <span className="font-semibold text-slate-900 text-xs truncate max-w-xs" title={t.fileName}>
                        {t.fileName}
                      </span>
                    </div>
                  </TableCell>

                  <TableCell className="text-xs text-slate-700">
                    <div>{t.senderName}</div>
                    <div className="text-[10px] text-slate-400 font-mono">{t.senderSession}</div>
                  </TableCell>

                  <TableCell className="text-xs text-slate-700">
                    <div>{t.receiverName}</div>
                    <div className="text-[10px] text-slate-400 font-mono">{t.receiverSession}</div>
                  </TableCell>

                  <TableCell className="text-xs font-mono text-slate-700">
                    {formatBytes(t.fileSize)}
                  </TableCell>

                  <TableCell>
                    <Badge variant="p2p" size="sm">
                      <Zap className="w-2.5 h-2.5 text-teal-600" />
                      {t.mode}
                    </Badge>
                  </TableCell>

                  <TableCell>
                    <div className="space-y-1">
                      <div className="text-[11px] font-medium text-slate-700">{t.progressPercent}%</div>
                      <Progress value={t.progressPercent} status={t.status} className="h-1.5" />
                    </div>
                  </TableCell>

                  <TableCell className="text-right">
                    <Badge variant={t.status === 'completed' ? 'success' : 'p2p'} size="sm">
                      {t.status}
                    </Badge>
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
