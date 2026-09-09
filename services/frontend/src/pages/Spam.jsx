import EmailListView from '../components/EmailListView';

const SPAM_FILTERS = { label: 'SPAM' };

export default function Spam() {
  return (
    <EmailListView
      title="Spam"
      filters={SPAM_FILTERS}
      emptyMessage="No hay spam"
    />
  );
}