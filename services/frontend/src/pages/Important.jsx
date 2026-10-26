import { useEffect } from 'react';
import EmailListView from '../components/EmailListView';
import { useScreenScope, EMPTY_SCOPE } from '../context/ScreenScopeContext';

const IMPORTANT_FILTERS = { important: 'true' };

export default function Important() {
  const { setScope } = useScreenScope();

  useEffect(() => {
    setScope({ folder: 'important', criteria: IMPORTANT_FILTERS, description: 'Importantes' });
    return () => setScope(EMPTY_SCOPE);
  }, [setScope]);

  return (
    <EmailListView
      title="Importantes"
      filters={IMPORTANT_FILTERS}
      emptyMessage="No hay mails importantes"
    />
  );
}
