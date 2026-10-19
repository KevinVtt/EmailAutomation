import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import EmailListView from '../components/EmailListView';
import { useScreenScope, EMPTY_SCOPE } from '../context/ScreenScopeContext';

export default function Inbox() {
  // Chat filters from the Dashboard navigate here with criteria in router state.
  const location = useLocation();
  const criteria = location.state?.criteria;
  const { setScope } = useScreenScope();

  useEffect(() => {
    setScope({ folder: 'inbox', criteria: criteria || {}, description: 'Bandeja de entrada' });
    // Al salir de la lista el asistente vuelve a no tener alcance.
    return () => setScope(EMPTY_SCOPE);
  }, [criteria, setScope]);

  return (
    <EmailListView
      title="Bandeja de entrada"
      emptyMessage="Tus emails aparecerán aquí"
      filters={criteria || {}}
    />
  );
}
