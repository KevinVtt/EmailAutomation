import { useState, useCallback, useEffect, useRef } from 'react';
import { useAuth } from '../hooks/useAuth';
import { useWebSocket } from '../hooks/useWebSocket';
import { api } from '../lib/api';
import EmailList from '../components/EmailList';
import EmailDetail from '../components/EmailDetail';
import ChatPanel from '../components/ChatPanel';
import { WifiOff } from 'lucide-react';

export default function Dashboard() {
  const { user } = useAuth();
  const [emails, setEmails] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selectedEmail, setSelectedEmail] = useState(null);
  const [chatResponse, setChatResponse] = useState(null);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [dateFrom, setDateFrom] = useState(null);
  const [dateTo, setDateTo] = useState(null);
  const [wsError, setWsError] = useState(null);
  const [activeCriteria, setActiveCriteria] = useState(null);
  const [syncStatus, setSyncStatus] = useState(null); // null | 'syncing' | 'success' | 'error'

  const buildFilters = useCallback((overrides = {}) => {
    const filters = {};
    if (dateFrom) filters.dateFrom = new Date(dateFrom).toISOString();
    if (dateTo) filters.dateTo = new Date(dateTo + 'T23:59:59').toISOString();
    return { ...filters, ...overrides };
  }, [dateFrom, dateTo]);

  const fetchEmails = useCallback(async (filters = {}, pageNum = 0) => {
    setLoading(true);
    setError(null);
    setPage(pageNum);
    try {
      const merged = { ...buildFilters(), ...filters };
      const data = Object.keys(merged).length > 0
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
  }, [buildFilters]);

  const onChat = useCallback((data) => {
    if (data.type === 'chat_response' && data.data) {
      setChatResponse(data.data);
    }
  }, []);

  const onEmails = useCallback((data) => {
    if (data.type === 'filtered_emails' && Array.isArray(data.emails)) {
      setEmails(data.emails);
      setLoading(false);
    }
  }, []);

  const onNotifications = useCallback((data) => {
    if (data.type === 'notification' && data.message === 'sync_complete') {
      setSyncStatus('success');
      setTimeout(() => setSyncStatus(null), 3000);
      fetchEmails(activeCriteria || {}, 0);
    } else if (data.type === 'notification' && typeof data.message === 'string' && data.message.startsWith('sync_error')) {
      console.error('Sync error:', data.message);
      setSyncStatus('error');
      setTimeout(() => setSyncStatus(null), 5000);
      setLoading(false);
    }
  }, [fetchEmails, activeCriteria]);

  useWebSocket(localStorage.getItem('accessToken'), {
    onEmails,
    onNotifications,
    onChat,
    onConnect: () => setWsError(null),
    onError: () => setWsError('Conexión WebSocket perdida. Reintentando...'),
  });

  const handleSync = useCallback(async (provider) => {
    try {
      setSyncStatus('syncing');
      await api.emails.sync(provider);
      // Fallback: poll sync status in case WebSocket notification doesn't arrive
      const pollInterval = setInterval(async () => {
        try {
          const status = await api.emails.syncStatus();
          if (!status.syncing) {
            clearInterval(pollInterval);
            setSyncStatus('success');
            setTimeout(() => setSyncStatus(null), 3000);
            fetchEmails(activeCriteria || {}, 0);
          }
        } catch {
          // Ignore poll errors
        }
      }, 2000);
      // Safety: stop polling after 3 minutes
      setTimeout(() => clearInterval(pollInterval), 180000);
    } catch (err) {
      console.error('Failed to trigger sync', err);
      setSyncStatus('error');
      setTimeout(() => setSyncStatus(null), 5000);
    }
  }, [fetchEmails, activeCriteria]);

  const handleSelectEmail = useCallback((email) => {
    setSelectedEmail(email);
  }, []);

  const handleCloseDetail = useCallback(() => {
    setSelectedEmail(null);
  }, []);

  const handlePageChange = useCallback((newPage) => {
    if (newPage < 0 || newPage >= totalPages) return;
    fetchEmails(activeCriteria || {}, newPage);
  }, [fetchEmails, totalPages, activeCriteria]);

  const handleDateFromChange = useCallback((value) => {
    setDateFrom(value);
    setActiveCriteria(null);
    setPage(0);
  }, []);

  const handleDateToChange = useCallback((value) => {
    setDateTo(value);
    setActiveCriteria(null);
    setPage(0);
  }, []);

  const handleClearDates = useCallback(() => {
    setDateFrom(null);
    setDateTo(null);
    setActiveCriteria(null);
    setPage(0);
  }, []);

  useEffect(() => {
    fetchEmails({}, page);
  }, [dateFrom, dateTo]);

  useEffect(() => {
    fetchEmails({}, 0);
  }, []);

  const autoSyncedRef = useRef(false);

  useEffect(() => {
    if (autoSyncedRef.current) return;
    const alreadySynced = sessionStorage.getItem('autoSynced');
    if (!alreadySynced && user) {
      sessionStorage.setItem('autoSynced', 'true');
      autoSyncedRef.current = true;
      handleSync('google');
    }
  }, [user]);

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      {wsError && (
        <div className="mb-4 flex items-center gap-2 rounded-lg bg-amber-50 border border-amber-200 px-4 py-2 text-sm text-amber-700">
          <WifiOff className="w-4 h-4 flex-shrink-0" />
          <span>{wsError}</span>
        </div>
      )}
      <div className="flex gap-6 h-[calc(100vh-7rem)]">
        <div className={`flex flex-col min-w-0 ${selectedEmail ? 'w-96 xl:w-[420px]' : 'flex-1'}`}>
          <EmailList
            emails={emails}
            loading={loading}
            error={error}
            syncStatus={syncStatus}
            selectedId={selectedEmail?.id}
            onSelect={handleSelectEmail}
            onRefresh={() => {
              setActiveCriteria(null);
              fetchEmails({}, 0);
            }}
            onSync={handleSync}
            page={page}
            totalPages={totalPages}
            onPageChange={handlePageChange}
            dateFrom={dateFrom}
            dateTo={dateTo}
            onDateFromChange={handleDateFromChange}
            onDateToChange={handleDateToChange}
            onClearDates={handleClearDates}
          />
        </div>
        {selectedEmail && (
          <div className="flex-1 min-w-0">
            <EmailDetail email={selectedEmail} onClose={handleCloseDetail} />
          </div>
        )}
        <div className={`flex-shrink-0 hidden lg:block ${selectedEmail ? 'w-80' : 'w-96'}`}>
          <ChatPanel
            onFiltersApplied={(criteria) => {
              setActiveCriteria(criteria);
              fetchEmails(criteria, 0);
            }}
            chatResponse={chatResponse}
            onChatConsumed={() => setChatResponse(null)}
          />
        </div>
      </div>
    </div>
  );
}
