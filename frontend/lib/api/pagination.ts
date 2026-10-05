export interface Page<T> {
  data: T[];
  pagination?: { hasMore: boolean };
  page?: { hasMore: boolean };
}

/** Collect offset pages while retaining the caller's cancellation signal. */
export async function collectPages<T>(load: (offset: number) => Promise<Page<T>>): Promise<T[]> {
  const items: T[] = [];
  for (let offset = 0; offset <= 100000;) {
    const response = await load(offset);
    items.push(...response.data);
    if (!(response.pagination?.hasMore || response.page?.hasMore)) return items;
    if (!response.data.length) throw new Error('Server trả về trang trống nhưng vẫn báo còn dữ liệu.');
    offset += response.data.length;
  }
  throw new Error('Danh sách vượt giới hạn tải. Hãy thu hẹp phạm vi truy vấn.');
}
