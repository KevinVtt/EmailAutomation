import { useLocation } from 'react-router-dom';
import EmailListView from '../components/EmailListView';

export default function Inbox() {
  // Chat filters from the Dashboard navigate here with criteria in router state.
  const location = useLocation();
  const criteria = location.state?.criteria;

  return (
    <EmailListView
      title="Bandeja de entrada"
      emptyMessage="Tus emails aparecerán aquí"
      filters={criteria || {}}
    />
  );
}