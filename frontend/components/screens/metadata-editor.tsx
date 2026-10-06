'use client';

import { useState } from 'react';
import { errorMessage } from '@/lib/api/client';
import { folderDestinations, folderPath, metadataUpdate, visibleParent } from '@/lib/api/folders';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { FileItem, FolderItem } from '@/lib/types';
import { Action, Dialog, Field, Notice } from '../system/primitives';

export function MetadataEditor({ kind, item, onClose }: {
  kind: 'file' | 'folder'; item: FileItem | FolderItem; onClose: () => void;
}) {
  const { folders, mutate } = useWorkspace();
  const [name, setName] = useState(item.name);
  const [destination, setDestination] = useState(visibleParent(folders, kind === 'file' ? (item as FileItem).folderId : (item as FolderItem).parentId) || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const choices = folderDestinations(folders, kind === 'folder' ? item.id : undefined);
  return <Dialog title={kind === 'file' ? 'Đổi tên hoặc di chuyển tài liệu' : 'Đổi tên hoặc di chuyển thư mục'}
    description="Thay đổi vị trí và tên trong workspace. Nội dung file trên thiết bị nguồn được giữ."
    onClose={() => !busy && onClose()}>
    <form className="form-stack" onSubmit={async event => {
      event.preventDefault(); setBusy(true); setError('');
      try {
        if (destination && !choices.some(folder => folder.id === destination)) throw new Error('Thư mục đích không còn khả dụng. Hãy chọn vị trí khác.');
        await mutate(`/api/${kind === 'file' ? 'files' : 'folders'}/${item.id}`, 'PATCH', metadataUpdate(kind, name, destination));
        onClose();
      } catch (failure) { setError(errorMessage(failure)); }
      finally { setBusy(false); }
    }}>
      {error && <Notice danger>{error}</Notice>}
      <Field label="Tên" value={name} onChange={event => setName(event.target.value)} required maxLength={255} disabled={busy} />
      <label className="field"><span>Vị trí</span><select value={destination} onChange={event => setDestination(event.target.value)} disabled={busy}>
        <option value="">Thư mục gốc</option>
        {choices.map(folder => <option key={folder.id} value={folder.id}>{folderPath(folders, folder.id).map(item => item.name).join(' / ')}</option>)}
      </select></label>
      <div className="form-actions"><Action tone="secondary" disabled={busy} onClick={onClose}>Hủy</Action><Action type="submit" busy={busy} disabled={!name.trim()}>Lưu thay đổi</Action></div>
    </form>
  </Dialog>;
}
