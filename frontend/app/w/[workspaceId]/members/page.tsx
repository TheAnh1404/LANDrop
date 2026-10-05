import { MembersScreen } from '@/components/screens/members';

export default function Page({ params }: { params: { workspaceId: string } }) {
  return <MembersScreen id={params.workspaceId} />;
}
