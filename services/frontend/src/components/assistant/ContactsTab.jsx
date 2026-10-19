import { useEffect, useRef, useState } from 'react';
import { api } from '../../lib/api';
import { EmptyState, ErrorState, LoadingState } from '../states';
import ComposeForm from './ComposeForm';

export default function ContactsTab() {
  const [contacts, setContacts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState(null);
  const enriched = useRef(new Set());

  useEffect(() => {
    let alive = true;
    api.contacts.list(20)
      .then((data) => {
        if (alive) setContacts(Array.isArray(data) ? data : []);
      })
      .catch(() => {
        if (alive) setError('No se pudieron cargar los contactos.');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const open = async (contact) => {
    setSelected(contact);
    if (contact.company && contact.topic) return;
    if (enriched.current.has(contact.id)) return;
    enriched.current.add(contact.id);

    try {
      const full = await api.contacts.enrich(contact.id);
      setSelected(full);
      setContacts((prev) => prev.map((c) => (c.id === full.id ? full : c)));
    } catch {
      // La ficha queda como vino; el usuario puede escribir igual.
    }
  };

  if (loading) return <LoadingState compact label="Cargando contactos..." />;
  if (error) return <ErrorState message={error} />;

  if (selected) {
    return <ComposeForm contact={selected} onBack={() => setSelected(null)} />;
  }

  if (contacts.length === 0) {
    return <EmptyState message="Todavía no hay contactos frecuentes." />;
  }

  return (
    <div className="overflow-y-auto flex-1 min-h-0 -mx-1 px-1">
      <ul className="space-y-1">
        {contacts.map((contact) => (
          <li key={contact.id}>
            <button
              type="button"
              data-testid="contact-row"
              onClick={() => open(contact)}
              className="w-full text-left px-2 py-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-700"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-medium text-gray-800 dark:text-gray-200 truncate">
                  {contact.displayName || contact.address}
                </span>
                <span className="text-xs text-gray-500 dark:text-gray-400 flex-shrink-0">
                  {contact.sentCount + contact.receivedCount}
                </span>
              </div>
              <p className="text-xs text-gray-500 dark:text-gray-400 truncate">
                {contact.address}
                {contact.company ? ` · ${contact.company}` : ''}
              </p>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
