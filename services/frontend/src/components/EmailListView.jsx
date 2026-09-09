import { useState, useCallback, useEffect, useRef } from 'react';
import { useAuth } from '../hooks/useAuth';
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

  // Keep filters in a ref so the fetch effect only re-runs on date changes.
  const filtersRef = useRef(filters);
  useEffect(() => {
    filtersRef.current = filters;
  }, [filters]);

  const buildFilters = useCallback((overrides = {}) => {
    const merged = { ...filtersRef.current };
    if (dateFrom) merged.dateFrom = new Date(dateFrom).toISOString();
    if (dateTo) merged.dateTo = new Date(dateTo + 'T23:59:59').toISOString();
    return { ...merged, ...overrides };
  }, [dateFrom, dateTo]);

  const fetchEmails = useCallback(async (pageNum = 0) => {
    setLoading(true);
    setError(null);
    setPage(pageNum);
    try {
      const merged = buildFilters();
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

  useEffect(() => {
    fetchEmails(0);
  }, [fetchEmails]);

  const handleSelectEmail = useCallback((email) => {
    setSelectedEmail(email);
  }, []);

  const handleEmailRead = useCallback((emailId) => {
    setEmails(prev => prev.map(e =>
      e.id === emailId ? { ...e, visto: true } : e
    ));
  }, []);

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
          />
        </div>
        {selectedEmail && (
          <div className="flex-1 min-w-0">
            <EmailDetail email={selectedEmail} onClose={handleCloseDetail} onRead={handleEmailRead} />
          </div>
        )}
      </div>
    </div>
  );
}