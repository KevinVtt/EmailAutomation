import { useEffect } from 'react';
import EmailListView from '../components/EmailListView';
import { useScreenScope, EMPTY_SCOPE } from '../context/ScreenScopeContext';

const STARRED_FILTERS = { destacados: true };

export default function Starred() {
  const { setScope } = useScreenScope();

  useEffect(() => {
    setScope({ folder: 'starred', criteria: { destacados: 'true' }, description: 'Destacados' });
    return () => setScope(EMPTY_SCOPE);
  }, [setScope]);

  return (
    <EmailListView
      title="Destacados"
      filters={STARRED_FILTERS}
      emptyMessage="No tienes emails destacados"
    />
  );
}
