'use client';

import React, { useEffect, useState } from 'react';
import { ChevronRight, FileText, Folder, FolderPlus, Info, Pencil, Plus, Search, Trash2 } from 'lucide-react';
import { errorMessage } from '@/lib/api/client';
import { folderPath, visibleParent } from '@/lib/api/folders';
import { useWorkspace } from '@/lib/data/workspace-provider';
import { useSession } from '@/lib/session/session-provider';
import { FileItem, FolderItem } from '@/lib/types';
import { formatBytes } from '@/lib/utils';
import { Action, Dialog, Empty, Feedback, Field, FileGlyph, Heading, Notice, Panel, SearchBox, Tag } from '../system/primitives';
import { MetadataEditor } from './metadata-editor';

type Target = { kind: 'file'; item: FileItem } | { kind: 'folder'; item: FolderItem };

export function FilesScreen({ id }: { id: string }) {
    const { files, folders, members, loading, error, refresh, mutate, workspace } = useWorkspace();
    const { mode, user } = useSession();
    const [query, setQuery] = useState('');
    const [folder, setFolder] = useState<string | null>(null);
    const [dialog, setDialog] = useState<'folder' | 'file' | null>(null);
    const [detail, setDetail] = useState<FileItem | null>(null);
    const [editing, setEditing] = useState<Target | null>(null);
    const [deleting, setDeleting] = useState<Target | null>(null);
    const [name, setName] = useState('');
    const [size, setSize] = useState('');
    const [hash, setHash] = useState('');
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState('');

    const admin = workspace?.role === 'OWNER' || workspace?.role === 'ADMIN';
    const ownerName = (file: FileItem) => members.find(member => member.userId === file.ownerId)?.name || file.ownerName || file.ownerId.slice(0, 8);
    const canEditFile = (file: FileItem) => mode === 'live' && (admin || file.ownerId === user?.id);
    const canEditFolder = (item: FolderItem) => mode === 'live' && (admin || !item.createdById || item.createdById === user?.id);
    const filtered = files.filter(item => query
        ? (item.name + ' ' + ownerName(item)).toLocaleLowerCase().includes(query.toLocaleLowerCase())
        : visibleParent(folders, item.folderId) === folder);
    const childFolders = folders.filter(item => visibleParent(folders, item.parentId) === folder);
    const chain = folderPath(folders, folder);
    const folderNotEmpty = deleting?.kind === 'folder' && (
        files.some(item => item.folderId === deleting.item.id) || folders.some(item => item.parentId === deleting.item.id)
    );

    useEffect(() => {
        setFolder(null); setQuery(''); setDetail(null); setEditing(null); setDeleting(null);
    }, [id]);
    useEffect(() => {
        if (loading) return;
        if (folder && !folders.some(item => item.id === folder)) setFolder(null);
        const fileId = new URLSearchParams(window.location.search).get('file');
        if (fileId) setDetail(files.find(item => item.id === fileId) || null);
    }, [files, folders, loading, folder]);

    const open = (type: 'folder' | 'file') => {
        setDialog(type); setName(''); setSize(''); setHash(''); setFailure('');
    };
    const openFolder = (folderId: string | null) => { setFolder(folderId); setQuery(''); };

    return <>
        <Heading eyebrow="TÀI LIỆU CỦA NHÓM" title="Kho tài liệu"
            description="Tìm thông tin file, sắp xếp thư mục và xác định người đang giữ nội dung."
            action={<>
                <Action tone="secondary" onClick={() => open('folder')} disabled={!workspace || loading}><FolderPlus size={15} />Tạo thư mục</Action>
                <Action onClick={() => open('file')} disabled={!workspace || loading}><Plus size={15} />Đăng ký tài liệu</Action>
            </>} />
        <Notice><strong>Nội dung nằm trên thiết bị nguồn.</strong> Server lưu thông tin tài liệu; khả năng nhận file phụ thuộc thiết bị nguồn và transport. Trình duyệt hiện chưa có cầu nối tới engine TCP.</Notice>
        <Feedback loading={loading} error={error} retry={refresh} />
        <div className="toolbar">
            <Tag>Tài liệu trong nhóm</Tag>
            <SearchBox value={query} onChange={setQuery} placeholder="Tìm tên file hoặc người đăng ký…" />
        </div>
        <div className="folder-path">
            <button onClick={() => openFolder(null)}><Folder size={14} />Thư mục gốc</button>
            {chain.map(item => <React.Fragment key={item.id}><ChevronRight size={13} /><button onClick={() => openFolder(item.id)}>{item.name}</button></React.Fragment>)}
        </div>
        {!query && !!childFolders.length && <div className="folder-grid">
            {childFolders.map(item => <div className="folder-tile folder-card" key={item.id}>
                <button className="folder-open" onClick={() => openFolder(item.id)}><Folder size={22} /><strong>{item.name}</strong></button>
                {canEditFolder(item) && <div className="folder-actions">
                    <Action tone="ghost" aria-label={'Sửa thư mục ' + item.name} onClick={() => setEditing({ kind: 'folder', item })}><Pencil size={13} /></Action>
                    <Action tone="ghost" aria-label={'Xóa thư mục ' + item.name} onClick={() => { setDeleting({ kind: 'folder', item }); setFailure(''); }}><Trash2 size={13} /></Action>
                </div>}
            </div>)}
        </div>}
        <Panel>
            <div className="table-wrap"><table className="data-table">
                <thead><tr><th>TÊN TÀI LIỆU</th><th>DUNG LƯỢNG</th><th>NGƯỜI ĐĂNG KÝ</th><th>NGUỒN FILE</th><th><span className="sr-only">Thao tác</span></th></tr></thead>
                <tbody>{filtered.map(file => <tr key={file.id}>
                    <td><button className="name-cell" onClick={() => setDetail(file)}><FileGlyph name={file.name} /><div><strong>{file.name}</strong><small>{file.type}</small></div></button></td>
                    <td>{formatBytes(file.size)}</td><td>{ownerName(file)}</td>
                    <td><Tag tone={mode === 'demo' && file.availability !== 'offline' ? 'green' : 'neutral'}>{mode === 'demo' ? file.availability === 'offline' ? 'Offline mẫu' : 'Nguồn mẫu' : 'Chưa xác nhận'}</Tag></td>
                    <td className="actions-cell">
                        <Action tone="ghost" onClick={() => setDetail(file)} aria-label={'Chi tiết ' + file.name}><Info size={15} /></Action>
                        {canEditFile(file) && <>
                            <Action tone="ghost" onClick={() => setEditing({ kind: 'file', item: file })} aria-label={'Sửa ' + file.name}><Pencil size={15} /></Action>
                            <Action tone="ghost" onClick={() => { setDeleting({ kind: 'file', item: file }); setFailure(''); }} aria-label={'Xóa ' + file.name}><Trash2 size={15} /></Action>
                        </>}
                    </td>
                </tr>)}</tbody>
            </table></div>
            {!loading && !filtered.length && <Empty icon={<Search size={23} />} title={query ? 'Không có file phù hợp' : 'Chưa có tài liệu trong thư mục'} description={query ? 'Thử tìm theo tên khác.' : 'Đăng ký thông tin file hoặc chọn thư mục khác.'} />}
            <div className="table-footer">{filtered.length} tài liệu hiển thị · {files.length} metadata đã tải</div>
        </Panel>
        {dialog && <Dialog title={dialog === 'folder' ? 'Tạo thư mục' : 'Đăng ký tài liệu của nhóm'}
            description={dialog === 'file' ? 'Lưu thông tin file trong workspace. Nội dung tiếp tục nằm trên thiết bị nguồn.' : 'Thư mục mới được tạo trong vị trí bạn đang mở.'}
            onClose={() => !busy && setDialog(null)}>
            <form className="form-stack" onSubmit={async event => {
                event.preventDefault(); setBusy(true); setFailure('');
                try {
                    if (mode === 'demo') throw new Error('Kết nối server thật để đăng ký tài liệu hoặc thư mục.');
                    if (dialog === 'folder') await mutate('/api/workspaces/' + id + '/folders', 'POST', { name: name.trim(), parentId: folder });
                    else {
                        const bytes = Number(size);
                        if (!Number.isSafeInteger(bytes) || bytes < 0) throw new Error('Dung lượng phải là số nguyên không âm trong giới hạn an toàn của trình duyệt.');
                        await mutate('/api/workspaces/' + id + '/files', 'POST', {
                            name: name.trim(), size: bytes, mimeType: 'application/octet-stream', hash: hash.trim() || null, folderId: folder, storageType: 'LAN_ONLY',
                        });
                    }
                    setDialog(null);
                } catch (failure) { setFailure(errorMessage(failure)); }
                finally { setBusy(false); }
            }}>
                {mode === 'demo' && <Notice>Chế độ demo chỉ xem mẫu. Thao tác này cần server thật.</Notice>}
                {failure && <Notice danger>{failure}</Notice>}
                <Field label={dialog === 'folder' ? 'Tên thư mục' : 'Tên file'} value={name} onChange={event => setName(event.target.value)} required maxLength={255} disabled={busy} />
                {dialog === 'file' && <>
                    <Field label="Dung lượng (bytes)" type="number" min={0} max={Number.MAX_SAFE_INTEGER} step={1} value={size} onChange={event => setSize(event.target.value)} required disabled={busy} />
                    <Field label="SHA-256 (nếu đã có)" value={hash} onChange={event => setHash(event.target.value)} pattern="[A-Fa-f0-9]{64}" placeholder="64 ký tự hex" disabled={busy} hint="Thông tin đăng ký; chưa xác minh nội dung qua trình duyệt." />
                </>}
                <div className="form-actions"><Action tone="secondary" disabled={busy} onClick={() => setDialog(null)}>Hủy</Action><Action type="submit" busy={busy} disabled={mode === 'demo' || !name.trim()}>Lưu thông tin</Action></div>
            </form>
        </Dialog>}
        {detail && <Dialog title={detail.name} description="Thông tin đăng ký tài liệu trong nhóm" onClose={() => setDetail(null)}>
            <div className="dialog-content">
                <dl className="detail-grid"><dt>Dung lượng</dt><dd>{formatBytes(detail.size)}</dd><dt>Người đăng ký</dt><dd>{ownerName(detail)}</dd><dt>Đăng ký lúc</dt><dd>{new Date(detail.createdAt).toLocaleString('vi-VN')}</dd><dt>SHA-256</dt><dd>{detail.sha256 || 'Chưa đăng ký checksum'}</dd><dt>Nguồn nội dung</dt><dd>Thiết bị nguồn trong mạng LAN</dd></dl>
                <div className="mt-5"><Notice>Để tải nội dung cần thiết bị nguồn và runtime truyền tệp. Giao diện không tạo liên kết tải khi chỉ có metadata.</Notice></div>
                <Action tone="secondary" className="w-full" onClick={() => setDetail(null)}>Đóng</Action>
            </div>
        </Dialog>}
        {editing && <MetadataEditor key={editing.item.id} kind={editing.kind} item={editing.item} onClose={() => setEditing(null)} />}
        {deleting && <Dialog title={deleting.kind === 'file' ? 'Xóa tài liệu khỏi workspace?' : 'Xóa thư mục?'}
            description={'“' + deleting.item.name + '” sẽ bị đánh dấu đã xóa trên server. Nội dung file ở thiết bị nguồn được giữ.'}
            onClose={() => !busy && setDeleting(null)}>
            <div className="dialog-content">
                {failure && <Notice danger>{failure}</Notice>}
                {folderNotEmpty && <Notice>Thư mục vẫn chứa tài liệu hoặc thư mục con. Di chuyển chúng sang vị trí khác trước khi xóa thư mục.</Notice>}
                <div className="form-actions">
                    <Action tone="secondary" disabled={busy} onClick={() => setDeleting(null)}>Hủy</Action>
                    <Action tone="danger" busy={busy} disabled={!!folderNotEmpty} onClick={async () => {
                        setBusy(true); setFailure('');
                        try {
                            await mutate('/api/' + (deleting.kind === 'file' ? 'files' : 'folders') + '/' + deleting.item.id, 'DELETE');
                            if (folder === deleting.item.id) setFolder(null);
                            setDeleting(null);
                        } catch (failure) { setFailure(errorMessage(failure)); }
                        finally { setBusy(false); }
                    }}>Xóa {deleting.kind === 'file' ? 'tài liệu' : 'thư mục'}</Action>
                </div>
            </div>
        </Dialog>}
    </>;
}
