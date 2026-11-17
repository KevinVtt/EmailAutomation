import { useEffect } from 'react';
import EmailListView from '../components/EmailListView';
import { useScreenScope, EMPTY_SCOPE } from '../context/ScreenScopeContext';

const TRASH_FILTERS = { label: 'TRASH' };

export default function Trash() {
  const { setScope } = useScreenScope();

  useEffect(() => {
    setScope({ folder: 'trash', criteria: TRASH_FILTERS, description: 'Papelera' });
    return () => setScope(EMPTY_SCOPE);
  }, [setScope]);

  return (
    <EmailListView
      title="Papelera"
      filters={TRASH_FILTERS}
      folder="trash"
      emptyMessage="La papelera está vacía"
    />
  );
}
