'use client';

import React, { useState } from 'react';
import { WorkspaceTopBar } from '@/components/layout/workspace-topbar';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/components/ui/card';
import { FileIcon, Breadcrumb, EmptyState } from '@/components/ui/file-icon';
import { SearchInput } from '@/components/ui/input';
import { Modal } from '@/components/ui/modal';
import { ConfirmDialog } from '@/components/ui/modal';
import { Input } from '@/components/ui/input';
import { useTransfer } from '@/lib/transfer/transfer-context';
import { MOCK_FILES, MOCK_FOLDERS, MOCK_WORKSPACES } from '@/lib/mock-data';
import { FileItem, FolderItem } from '@/lib/types';
import { formatBytes, formatDate } from '@/lib/utils';
import {
  UploadCloud,
  FolderPlus,
  Send,
  Edit2,
  Trash2,
  Folder,
  ShieldCheck,
  Zap,
  X,
  Copy,
  Check,
  Search,
} from 'lucide-react';

export default function FileManagerPage({
  params,
}: {
  params: { workspaceId: string };
}) {
  const { openSendModal } = useTransfer();
  const workspace =
    MOCK_WORKSPACES.find((w) => w.id === params.workspaceId) || MOCK_WORKSPACES[0];

  const [currentFolderId, setCurrentFolderId] = useState<string | null>(null);
  const [files, setFiles] = useState<FileItem[]>(
    MOCK_FILES[params.workspaceId] || MOCK_FILES.ws_dev || []
  );
  const [folders, setFolders] = useState<FolderItem[]>(
    MOCK_FOLDERS[params.workspaceId] || MOCK_FOLDERS.ws_dev || []
  );
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFile, setSelectedFile] = useState<FileItem | null>(files[0] || null);
  const [copiedHash, setCopiedHash] = useState(false);

  // Modals
  const [isNewFolderOpen, setIsNewFolderOpen] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');
  const [renameTarget, setRenameTarget] = useState<FileItem | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<FileItem | null>(null);

  // Breadcrumbs
  const breadcrumbItems = [
    { label: 'All Files', id: null },
    ...(currentFolderId
      ? [{ label: folders.find((f) => f.id === currentFolderId)?.name || 'Folder', id: currentFolderId }]
      : []),
  ];

  const handleCreateFolder = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newFolderName.trim()) return;

    const newFolder: FolderItem = {
      id: `fld_${Date.now().toString(36)}`,
      workspaceId: params.workspaceId,
      parentId: currentFolderId,
      name: newFolderName.trim(),
      filesCount: 0,
      createdAt: new Date().toISOString(),
      modifiedAt: new Date().toISOString(),
    };

    setFolders([...folders, newFolder]);
    setIsNewFolderOpen(false);
    setNewFolderName('');
  };

  const handleRename = (e: React.FormEvent) => {
    e.preventDefault();
    if (!renameTarget || !renameValue.trim()) return;

    setFiles((prev) =>
      prev.map((f) => (f.id === renameTarget.id ? { ...f, name: renameValue.trim() } : f))
    );
    if (selectedFile?.id === renameTarget.id) {
      setSelectedFile({ ...selectedFile, name: renameValue.trim() });
    }
    setRenameTarget(null);
  };

  const handleDelete = () => {
    if (!deleteTarget) return;
    setFiles((prev) => prev.filter((f) => f.id !== deleteTarget.id));
    if (selectedFile?.id === deleteTarget.id) {
      setSelectedFile(null);
    }
    setDeleteTarget(null);
  };

  const handleCopyChecksum = (sha: string) => {
    navigator.clipboard.writeText(sha);
    setCopiedHash(true);
    setTimeout(() => setCopiedHash(false), 2000);
  };

  const filteredFiles = files.filter((f) => {
    if (searchQuery.trim()) {
      return (
        f.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        f.ownerName.toLowerCase().includes(searchQuery.toLowerCase()) ||
        f.type.toLowerCase().includes(searchQuery.toLowerCase())
      );
    }
    return true;
  });

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-white">
      {/* TopBar with Actions */}
      <WorkspaceTopBar
        title="File Manager"
        subtitle="Manage workspace repositories, local files, and direct browser P2P transfers"
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="xs"
              onClick={() => setIsNewFolderOpen(true)}
              leftIcon={<FolderPlus className="w-3.5 h-3.5" />}
            >
              New Folder
            </Button>
            <Button
              variant="primary"
              size="xs"
              onClick={() => openSendModal()}
              leftIcon={<UploadCloud className="w-3.5 h-3.5" />}
            >
              Upload / Send P2P
            </Button>
          </div>
        }
      />

      {/* Main Content Area */}
      <div className="flex-1 flex min-h-0 overflow-hidden">
        {/* Files Explorer (Left) */}
        <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
          {/* Breadcrumb & Filter Bar */}
          <div className="px-6 py-3 border-b border-slate-200/80 bg-slate-50/60 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <Breadcrumb items={breadcrumbItems} onNavigate={(id) => setCurrentFolderId(id)} />

            <div className="flex items-center gap-3">
              <div className="w-48 sm:w-64">
                <SearchInput
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Filter files..."
                  className="h-8 text-xs bg-white"
                />
              </div>
              <span className="text-xs text-slate-400 whitespace-nowrap">
                {filteredFiles.length} files
              </span>
            </div>
          </div>

          {/* Table Container */}
          <div className="flex-1 overflow-y-auto">
            {filteredFiles.length === 0 && folders.length === 0 ? (
              <div className="p-12">
                <EmptyState
                  title="No files in this folder"
                  description="Upload a file or send one peer-to-peer using WebRTC DataChannel."
                  action={
                    <Button variant="primary" size="sm" onClick={() => openSendModal()}>
                      Send File
                    </Button>
                  }
                />
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[36%]">Name</TableHead>
                    <TableHead className="w-[14%]">Owner</TableHead>
                    <TableHead className="w-[12%]">Size</TableHead>
                    <TableHead className="w-[14%]">Modified</TableHead>
                    <TableHead className="w-[16%]">Availability</TableHead>
                    <TableHead className="w-[8%] text-right">Actions</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {/* Folders in Root/Current */}
                  {!searchQuery &&
                    folders.map((folder) => (
                      <TableRow
                        key={folder.id}
                        onClick={() => setCurrentFolderId(folder.id)}
                        className="cursor-pointer hover:bg-slate-50"
                      >
                        <TableCell>
                          <div className="flex items-center gap-2.5">
                            <Folder className="w-4 h-4 text-amber-500 fill-amber-500/20 flex-shrink-0" />
                            <span className="font-semibold text-slate-800 text-xs truncate">
                              {folder.name}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell className="text-xs text-slate-500">Workspace</TableCell>
                        <TableCell className="text-xs text-slate-400 font-mono">
                          {folder.filesCount} items
                        </TableCell>
                        <TableCell className="text-xs text-slate-500">
                          {formatDate(folder.modifiedAt)}
                        </TableCell>
                        <TableCell>
                          <Badge variant="neutral" size="sm">
                            Directory
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            variant="ghost"
                            size="xs"
                            onClick={(e) => {
                              e.stopPropagation();
                              setCurrentFolderId(folder.id);
                            }}
                          >
                            Open
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}

                  {/* Files List */}
                  {filteredFiles.map((file) => {
                    const isSelected = selectedFile?.id === file.id;
                    return (
                      <TableRow
                        key={file.id}
                        onClick={() => setSelectedFile(file)}
                        className={`cursor-pointer transition-colors ${
                          isSelected ? 'bg-indigo-50/50 hover:bg-indigo-50/70' : 'hover:bg-slate-50'
                        }`}
                      >
                        <TableCell>
                          <div className="flex items-center gap-2.5 min-w-0">
                            <FileIcon type={file.type} className="flex-shrink-0" />
                            <span
                              className="font-medium text-slate-900 text-xs truncate max-w-sm"
                              title={file.name}
                            >
                              {file.name}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell className="text-xs text-slate-600 truncate">
                          {file.ownerName}
                        </TableCell>
                        <TableCell className="text-xs text-slate-600 font-mono">
                          {formatBytes(file.size)}
                        </TableCell>
                        <TableCell className="text-xs text-slate-500">
                          {formatDate(file.modifiedAt)}
                        </TableCell>
                        <TableCell>
                          <Badge
                            variant={
                              file.availability === 'local'
                                ? 'success'
                                : file.availability === 'peer'
                                ? 'p2p'
                                : 'neutral'
                            }
                            size="sm"
                          >
                            {file.availability === 'local' ? (
                              'Available locally'
                            ) : file.availability === 'peer' ? (
                              <span className="flex items-center gap-1">
                                <Zap className="w-2.5 h-2.5 text-teal-600" />
                                {file.availableFromDevice || 'Peer Session'}
                              </span>
                            ) : (
                              'Offline'
                            )}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right">
                          <div
                            className="flex items-center justify-end gap-1"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <Button
                              variant="ghost"
                              size="xs"
                              onClick={() => openSendModal({ name: file.name, size: file.size })}
                              title="Send file via P2P"
                            >
                              <Send className="w-3.5 h-3.5 text-slate-500 hover:text-indigo-600" />
                            </Button>
                          </div>
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </div>
        </div>

        {/* Right-Side File Detail Panel */}
        {selectedFile && (
          <aside className="w-80 border-l border-slate-200 bg-slate-50/50 p-6 flex flex-col justify-between overflow-y-auto animate-in slide-in-from-right-10 duration-200 flex-shrink-0">
            <div className="space-y-6">
              {/* Header */}
              <div className="flex items-start justify-between">
                <div className="w-12 h-12 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                  <FileIcon type={selectedFile.type} className="w-6 h-6 text-indigo-600" />
                </div>
                <button
                  type="button"
                  onClick={() => setSelectedFile(null)}
                  className="p-1.5 text-slate-400 hover:text-slate-600 rounded-lg hover:bg-slate-200/60"
                  aria-label="Close details"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              <div>
                <h3
                  className="text-sm font-bold text-slate-900 break-words"
                  title={selectedFile.name}
                >
                  {selectedFile.name}
                </h3>
                <div className="text-xs text-slate-500 font-mono mt-0.5">
                  {formatBytes(selectedFile.size)} • {selectedFile.type.toUpperCase()}
                </div>
              </div>

              {/* Primary Actions */}
              <div className="flex gap-2">
                <Button
                  variant="primary"
                  size="sm"
                  className="w-full"
                  onClick={() =>
                    openSendModal({ name: selectedFile.name, size: selectedFile.size })
                  }
                  leftIcon={<Send className="w-3.5 h-3.5" />}
                >
                  Send P2P
                </Button>
              </div>

              {/* File Metadata Details */}
              <div className="space-y-3 pt-4 border-t border-slate-200 text-xs">
                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Availability:</span>
                  <Badge
                    variant={
                      selectedFile.availability === 'local'
                        ? 'success'
                        : selectedFile.availability === 'peer'
                        ? 'p2p'
                        : 'neutral'
                    }
                    size="sm"
                  >
                    {selectedFile.availability === 'local'
                      ? 'Local Browser'
                      : selectedFile.availability === 'peer'
                      ? `From ${selectedFile.availableFromDevice}`
                      : 'Offline'}
                  </Badge>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Owner:</span>
                  <span className="font-medium text-slate-800">{selectedFile.ownerName}</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Workspace:</span>
                  <span className="text-slate-700">{workspace.name}</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Folder:</span>
                  <span className="font-mono text-slate-700">
                    {selectedFile.folderPath || '/'}
                  </span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Created:</span>
                  <span className="text-slate-700">{formatDate(selectedFile.createdAt)}</span>
                </div>

                <div className="flex items-center justify-between">
                  <span className="text-slate-500">Modified:</span>
                  <span className="text-slate-700">{formatDate(selectedFile.modifiedAt)}</span>
                </div>

                {/* Secondary Checksum preview with Copy action */}
                <div className="pt-2 border-t border-slate-200">
                  <div className="flex items-center justify-between text-slate-500 mb-1">
                    <span className="flex items-center gap-1.5 font-medium">
                      <ShieldCheck className="w-3.5 h-3.5 text-emerald-600" />
                      SHA-256 Integrity:
                    </span>
                    <button
                      type="button"
                      onClick={() => handleCopyChecksum(selectedFile.sha256)}
                      className="text-[10px] text-indigo-600 hover:text-indigo-800 flex items-center gap-0.5"
                    >
                      {copiedHash ? (
                        <>
                          <Check className="w-3 h-3 text-emerald-600" />
                          <span>Copied</span>
                        </>
                      ) : (
                        <>
                          <Copy className="w-3 h-3" />
                          <span>Copy</span>
                        </>
                      )}
                    </button>
                  </div>
                  <div className="font-mono text-[10px] text-slate-600 bg-white p-2 rounded border border-slate-200 break-all select-all">
                    {selectedFile.sha256}
                  </div>
                </div>
              </div>
            </div>

            {/* Bottom Actions: Rename / Delete */}
            <div className="pt-4 border-t border-slate-200 flex items-center justify-between">
              <Button
                variant="ghost"
                size="xs"
                onClick={() => {
                  setRenameTarget(selectedFile);
                  setRenameValue(selectedFile.name);
                }}
                leftIcon={<Edit2 className="w-3.5 h-3.5" />}
              >
                Rename
              </Button>
              <Button
                variant="ghost"
                size="xs"
                className="text-red-600 hover:text-red-700 hover:bg-red-50"
                onClick={() => setDeleteTarget(selectedFile)}
                leftIcon={<Trash2 className="w-3.5 h-3.5" />}
              >
                Delete
              </Button>
            </div>
          </aside>
        )}
      </div>

      {/* New Folder Modal */}
      <Modal
        isOpen={isNewFolderOpen}
        onClose={() => setIsNewFolderOpen(false)}
        title="Create New Folder"
        description="Organize your team files and project assets"
        size="sm"
      >
        <form onSubmit={handleCreateFolder} className="space-y-4">
          <Input
            label="Folder Name"
            value={newFolderName}
            onChange={(e) => setNewFolderName(e.target.value)}
            placeholder="e.g. Design Assets, Releases"
            required
            autoFocus
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" type="button" onClick={() => setIsNewFolderOpen(false)}>
              Cancel
            </Button>
            <Button variant="primary" size="sm" type="submit">
              Create Folder
            </Button>
          </div>
        </form>
      </Modal>

      {/* Rename Modal */}
      <Modal
        isOpen={!!renameTarget}
        onClose={() => setRenameTarget(null)}
        title="Rename File"
        size="sm"
      >
        <form onSubmit={handleRename} className="space-y-4">
          <Input
            label="New Name"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            required
            autoFocus
          />
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" type="button" onClick={() => setRenameTarget(null)}>
              Cancel
            </Button>
            <Button variant="primary" size="sm" type="submit">
              Save
            </Button>
          </div>
        </form>
      </Modal>

      {/* Confirm Delete Dialog */}
      <ConfirmDialog
        isOpen={!!deleteTarget}
        onClose={() => setDeleteTarget(null)}
        onConfirm={handleDelete}
        title="Delete File"
        description={`Are you sure you want to delete "${deleteTarget?.name}"? Other team members will no longer be able to pull it via P2P.`}
        confirmText="Delete"
        variant="danger"
      />
    </div>
  );
}
