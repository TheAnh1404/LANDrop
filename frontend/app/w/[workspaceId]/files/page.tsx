import { FilesScreen } from '@/components/screens/files';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <FilesScreen id={params.workspaceId} />;
}
