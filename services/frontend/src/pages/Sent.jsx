import EmailListView from '../components/EmailListView';

export default function Sent() {
  return (
    <EmailListView
      title="Enviados"
      sentMode
      showDates={false}
      emptyMessage="No hay emails enviados"
    />
  );
}
