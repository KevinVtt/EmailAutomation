import EmailListView from '../components/EmailListView';

const STARRED_FILTERS = { isStarred: true };

export default function Starred() {
  return (
    <EmailListView
      title="Destacados"
      filters={STARRED_FILTERS}
      emptyMessage="No tienes emails destacados"
    />
  );
}