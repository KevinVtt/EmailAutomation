import { useState, useCallback, useEffect, useRef } from 'react';
import { useAuth } from '../hooks/useAuth';
import { useWebSocket } from '../hooks/useWebSocket';
import { api } from '../lib/api';
import EmailList from './EmailList';
import EmailDetail from './EmailDetail';

/**
 * Shared page for filtered email views (Bandeja de entrada, Destacados, Spam).
 * Fetches emails via api.emails.filter(filters) and renders EmailList + EmailDetail.
 * Sync controls are intentionally not rendered here — the Dashboard owns sync.
 */
export default function EmailListView({
  title,
  filters = {},
  emptyMessage,
  showDates = true,
  sentMode = false,
  folder = 'inbox',
}) {
  const { user } = useAuth();
  const [emails, setEmails] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selectedEmail, setSelectedEmail] = useState(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [dateFrom, setDateFrom] = useState(null);
  const [dateTo, setDateTo] = useState(null);
  // Búsqueda de texto de la carpeta (sin IA): se aplica con 300 ms de demora.
  const [query, setQuery] = useState('');
  const [activeQuery, setActiveQuery] = useState('');

  useEffect(() => {
    const timer = setTimeout(() => setActiveQuery(query.trim()), 300);
    return () => clearTimeout(timer);
  }, [query]);

  // La página actual también en ref: el refresh silencioso por WebSocket no debe
  // depender de la identidad de fetchEmails (si no, se re-suscribe en cada render).
  const pageRef = useRef(0);

  // Keep filters in a ref so the fetch effect only re-runs on date or filter changes.
  const filtersRef = useRef(filters);
  useEffect(() => {
    filtersRef.current = filters;
  }, [filters]);

  // Stable key: the parent may pass a new object with the same content on every render.
  const filtersKey = JSON.stringify(filters);

  const buildFilters = useCallback((overrides = {}) => {
    const merged = { ...filtersRef.current };
    // Sin la hora, "2026-10-20" se parsea como UTC y en Argentina cae el día anterior.
    if (dateFrom) merged.dateFrom = new Date(dateFrom + 'T00:00:00').toISOString();
    if (dateTo) {
      // El backend usa un límite superior exclusivo: el día completo termina a las
      // 00:00 del día siguiente, no en 23:59:59 (eso pierde el último segundo).
      const end = new Date(dateTo + 'T00:00:00');
      end.setDate(end.getDate() + 1);
      merged.dateTo = end.toISOString();
    }
    return { ...merged, ...overrides };
  }, [dateFrom, dateTo]);

  const fetchEmails = useCallback(async (pageNum = 0, { silent = false } = {}) => {
    if (!silent) setLoading(true);
    setError(null);
    setPage(pageNum);
    pageRef.current = pageNum;
    try {
      const merged = buildFilters();
      const data = sentMode
        ? await api.emails.sent(pageNum)
        : activeQuery
        ? await api.emails.search(activeQuery, folder, pageNum)
        : Object.keys(merged).length > 0
        ? await api.emails.filter(merged, pageNum)
        : await api.emails.list(pageNum);
      setEmails(data.content || []);
      setTotalPages(data.totalPages || 0);
    } catch (err) {
      console.error('Failed to fetch emails', err);
      setError(err.message || 'Error al cargar emails');
    } finally {
      setLoading(false);
    }
  }, [buildFilters, sentMode, activeQuery, folder]);

  useEffect(() => {
    fetchEmails(0);
  }, [fetchEmails, filtersKey]);

  // Un sync de fondo (o una acción sobre un email) llega por WebSocket: refrescar
  // sin pasar por el estado de loading, que si no la lista parpadea sola.
  useWebSocket(localStorage.getItem('accessToken'), {
    onNotifications: (data) => {
      if (data?.type !== 'notification') return;
      if (data.message === 'sync_complete' || data.message === 'emails_changed') {
        fetchEmails(pageRef.current, { silent: true });
      }
    },
  });

  const handleSelectEmail = useCallback((email) => {
    setSelectedEmail(email);
  }, []);

  const handleEmailRead = useCallback((emailId) => {
    setEmails(prev => prev.map(e =>
      e.id === emailId ? { ...e, visto: true } : e
    ));
  }, []);

  // Tras mover, restaurar o borrar, el mail sale de esta vista: hay que sacarlo de la lista o
  // sigue visible hasta el próximo fetch.
  const handleEmailAction = useCallback((actionType) => {
    if (!['trash', 'restore', 'delete'].includes(actionType) || !selectedEmail) return;
    setEmails(prev => prev.filter(e => e.id !== selectedEmail.id));
  }, [selectedEmail]);

  const handleCloseDetail = useCallback(() => {
    setSelectedEmail(null);
  }, []);

  const handlePageChange = useCallback((newPage) => {
    if (newPage < 0 || newPage >= totalPages) return;
    fetchEmails(newPage);
  }, [fetchEmails, totalPages]);

  const handleDateFromChange = useCallback((value) => {
    setDateFrom(value);
    setPage(0);
  }, []);

  const handleDateToChange = useCallback((value) => {
    setDateTo(value);
    setPage(0);
  }, []);

  const handleClearDates = useCallback(() => {
    setDateFrom(null);
    setDateTo(null);
    setPage(0);
  }, []);

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="flex gap-6 h-[calc(100vh-7rem)]">
        <div className={`flex flex-col min-w-0 ${selectedEmail ? 'w-[420px] xl:w-[480px]' : 'flex-1'}`}>
          {!sentMode && (
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={`Buscar en ${title}`}
              aria-label={`Buscar en ${title}`}
              className="input w-full text-sm mb-2 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100"
            />
          )}
          <EmailList
            title={title}
            emails={emails}
            loading={loading}
            error={error}
            selectedId={selectedEmail?.id}
            onSelect={handleSelectEmail}
            onRefresh={() => fetchEmails(0)}
            page={page}
            totalPages={totalPages}
            onPageChange={handlePageChange}
            dateFrom={dateFrom}
            dateTo={dateTo}
            onDateFromChange={handleDateFromChange}
            onDateToChange={handleDateToChange}
            onClearDates={handleClearDates}
            showSync={false}
            showDates={showDates}
            emptyMessage={emptyMessage}
            provider={user?.provider || 'google'}
            showRecipient={sentMode}
          />
        </div>
        {selectedEmail && (
          <div className="flex-1 min-w-0">
            <EmailDetail key={selectedEmail.id} email={selectedEmail} onClose={handleCloseDetail} onRead={handleEmailRead} onAction={handleEmailAction} readOnly={sentMode} />
          </div>
        )}
      </div>
    </div>
  );
}