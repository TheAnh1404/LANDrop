import { TransfersScreen } from '@/components/screens/transfers';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <TransfersScreen id={params.workspaceId} />;
}
