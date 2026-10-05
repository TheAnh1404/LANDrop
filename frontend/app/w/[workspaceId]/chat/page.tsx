'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

export default function ChatIndexPage({ params }: { params: { workspaceId: string } }) {
  const router = useRouter();

  useEffect(() => {
    router.replace(`/w/${params.workspaceId}/chat/c_general`);
  }, [params.workspaceId, router]);

  return (
    <div className="flex-1 flex items-center justify-center text-xs text-slate-400">
      Loading channel...
    </div>
  );
}
