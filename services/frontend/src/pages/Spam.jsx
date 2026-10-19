import { useEffect } from 'react';
import EmailListView from '../components/EmailListView';
import { useScreenScope, EMPTY_SCOPE } from '../context/ScreenScopeContext';

const SPAM_FILTERS = { label: 'SPAM' };

export default function Spam() {
  const { setScope } = useScreenScope();

  useEffect(() => {
    setScope({ folder: 'spam', criteria: { label: 'SPAM' }, description: 'Spam' });
    return () => setScope(EMPTY_SCOPE);
  }, [setScope]);

  return (
    <EmailListView
      title="Spam"
      filters={SPAM_FILTERS}
      emptyMessage="No hay spam"
    />
  );
}
