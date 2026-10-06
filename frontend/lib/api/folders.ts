import type { FolderItem } from '../types';

export function folderPath(folders: FolderItem[], id: string | null): FolderItem[] {
  const byId = new Map(folders.map(folder => [folder.id, folder]));
  const seen = new Set<string>();
  const path: FolderItem[] = [];
  while (id && !seen.has(id)) {
    seen.add(id);
    const folder = byId.get(id);
    if (!folder) break;
    path.unshift(folder);
    id = folder.parentId || null;
  }
  return path;
}

/** Missing/deleted parents are shown at the root instead of hiding their contents. */
export function visibleParent(folders: FolderItem[], parent?: string | null): string | null {
  return parent && folders.some(folder => folder.id === parent) ? parent : null;
}

export function folderDestinations(folders: FolderItem[], movingId?: string): FolderItem[] {
  if (!movingId) return folders;
  const byId = new Map(folders.map(folder => [folder.id, folder]));
  return folders.filter(candidate => {
    const seen = new Set<string>();
    let id: string | null = candidate.id;
    while (id) {
      if (id === movingId || seen.has(id)) return false;
      seen.add(id);
      id = byId.get(id)?.parentId || null;
    }
    return true;
  });
}

export function metadataUpdate(kind: 'file' | 'folder', name: string, destination: string) {
  if (!name.trim() || name.trim().length > 255) throw new Error('Tên phải có từ 1 đến 255 ký tự.');
  return { name: name.trim(), [kind === 'file' ? 'folderId' : 'parentId']: destination || null };
}
