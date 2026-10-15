import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useWebSocket } from '../hooks/useWebSocket';
import { api } from '../lib/api';
import ChatPanel from '../components/ChatPanel';
import { LoadingState, ErrorState } from '../components/states';
import { format } from 'date-fns';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  Mail, MailOpen, Star, ShieldAlert, RefreshCw, Loader2, AlertCircle,
  Check, Clock, Database, ChevronDown, WifiOff, BarChart3,
  Sparkles, AlertTriangle, X, Target,
} from 'lucide-react';

const STAT_CARDS = [
  { key: 'total', label: 'Total de emails', icon: Mail, iconClass: 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300' },
  { key: 'unread', label: 'No leídos', icon: MailOpen, iconClass: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400' },
  { key: 'starred', label: 'Destacados', icon: Star, iconClass: 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300' },
  { key: 'spam', label: 'Spam', icon: ShieldAlert, iconClass: 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400' },
];

function StatCard({ label, value, icon: Icon, iconClass }) {
  return (
    <div className="card flex items-center gap-4 dark:bg-gray-800 dark:border-gray-700">
      <div className={`w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0 ${iconClass}`}>
        <Icon className="w-6 h-6" />
      </div>
      <div className="min-w-0">
        <div className="text-2xl font-bold text-gray-900 dark:text-gray-100">{value.toLocaleString()}</div>
        <div className="text-sm text-gray-500 dark:text-gray-400 truncate">{label}</div>
      </div>
    </div>
  );
}

function BarChart({ data }) {
  const max = Math.max(...data.map((d) => d.count), 1);
  return (
    <div className="flex items-end gap-2 sm:gap-3 h-48">
      {data.map((d) => (
        <div key={d.label} className="flex-1 flex flex-col items-center gap-1 h-full min-w-0">
          <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{d.count}</span>
          <div className="flex-1 w-full flex items-end">
            <div
              data-testid="chart-bar"
              className="w-full rounded-t-md bg-primary-500 dark:bg-primary-400 transition-all duration-300"
              style={{ height: `${Math.max((d.count / max) * 100, 2)}%` }}
              title={`${d.label}: ${d.count} emails`}
            />
          </div>
          <span className="text-xs text-slate-500 dark:text-gray-500">{d.label}</span>
        </div>
      ))}
    </div>
  );
}

export default function Dashboard() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [chatResponse, setChatResponse] = useState(null);
  const [wsError, setWsError] = useState(null);
  const [syncStatus, setSyncStatus] = useState(null); // null | 'syncing' | 'success' | 'error'
  const [syncProgress, setSyncProgress] = useState({ current: 0, total: 0 });
  const [syncMode, setSyncMode] = useState(null); // null | 'incremental' | 'full'
  const [syncMenuOpen, setSyncMenuOpen] = useState(false);
  const [dailySummary, setDailySummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [summaryError, setSummaryError] = useState(null);
  const [prioritySummary, setPrioritySummary] = useState(null);
  const [priorityLoading, setPriorityLoading] = useState(true);
  const [priorityError, setPriorityError] = useState(null);
  const [urgentEmails, setUrgentEmails] = useState(null);
  const pollIntervalRef = useRef(null);
  const syncSafetyRef = useRef(null);

  const fetchStats = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.emails.stats();

      setStats({
        total: res.total || 0,
        unread: res.unread || 0,
        starred: res.starred || 0,
        spam: res.spam || 0,
        // Backend days are Buenos Aires dates; en-GB is the only toLocaleDateString
        // locale that guarantees padded dd/MM regardless of the user's ICU locale.
        perDay: (res.perDay || []).map((d) => ({
          label: new Date(`${d.date}T00:00:00`).toLocaleDateString('en-GB', {
            day: '2-digit',
            month: '2-digit',
          }),
          count: d.count,
        })),
      });
    } catch (err) {
      console.error('Failed to fetch stats', err);
      setError(err.message || 'Error al cargar estadísticas');
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchSummary = useCallback(async () => {
    setSummaryLoading(true);
    setSummaryError(null);
    try {
      const data = await api.emails.summary('today');
      setDailySummary(data);
    } catch (err) {
      console.error('Failed to fetch summary', err);
      setSummaryError(err.message || 'Error al cargar resumen');
    } finally {
      setSummaryLoading(false);
    }
  }, []);

  const fetchPrioritySummary = useCallback(async () => {
    setPriorityLoading(true);
    setPriorityError(null);
    try {
      const data = await api.emails.prioritySummary();
      setPrioritySummary(data);
    } catch (err) {
      console.error('Failed to fetch priority summary', err);
      setPriorityError(err.message || 'Error al cargar resumen priorizado');
    } finally {
      setPriorityLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStats();
    fetchSummary();
    fetchPrioritySummary();
  }, [fetchStats, fetchSummary, fetchPrioritySummary]);

  // Corta el poll y el timer de seguridad del sync en curso.
  const stopSyncPoll = useCallback(() => {
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
    }
    if (syncSafetyRef.current) {
      clearTimeout(syncSafetyRef.current);
      syncSafetyRef.current = null;
    }
  }, []);

  // Único punto de cierre del sync: siempre saca la UI de 'syncing' (antes el
  // timer de seguridad cortaba el poll pero dejaba el botón pegado para siempre).
  const finishSync = useCallback((status) => {
    stopSyncPoll();
    setSyncStatus(status);
    setSyncMode(null);
    if (status === 'success') fetchStats();
    setTimeout(() => {
      setSyncStatus(null);
      setSyncProgress({ current: 0, total: 0 });
    }, status === 'error' ? 5000 : 3000);
  }, [stopSyncPoll, fetchStats]);

  // Clear the sync-status poll if the dashboard unmounts mid-sync.
  useEffect(() => stopSyncPoll, [stopSyncPoll]);

  const handleSync = useCallback(async (provider, mode = 'incremental') => {
    try {
      setSyncStatus('syncing');
      setSyncMode(mode);
      setSyncProgress({ current: 0, total: 0 });

      if (mode === 'full') {
        await api.emails.syncFull(provider);
      } else {
        await api.emails.sync(provider);
      }

      // Fallback: poll sync status in case WebSocket notification doesn't arrive
      stopSyncPoll();
      pollIntervalRef.current = setInterval(async () => {
        try {
          const status = await api.emails.syncStatus();
          if (!status.syncing) {
            finishSync('success');
          }
        } catch {
          // Ignore poll errors
        }
      }, 2000);
      // Safety: si el poll no ve terminar la sync, liberamos la UI igual (antes
      // solo se cortaba el interval y el botón quedaba en "Sincronizando...").
      const timeout = mode === 'full' ? 600000 : 180000;
      syncSafetyRef.current = setTimeout(() => {
        if (pollIntervalRef.current) finishSync('error');
      }, timeout);
    } catch (err) {
      console.error('Failed to trigger sync', err);
      finishSync('error');
    }
  }, [finishSync, stopSyncPoll]);

  const onChat = useCallback((data) => {
    if (data.type === 'chat_response' && data.data) {
      setChatResponse(data.data);
    }
  }, []);

  const onNotifications = useCallback((data) => {
    if (data.type !== 'notification') return;

    const msg = data.message;
    if (msg === 'sync_complete') {
      finishSync('success');
    } else if (typeof msg === 'string' && msg.startsWith('sync_progress:')) {
      // sync_progress:current:total
      const parts = msg.split(':');
      const current = parseInt(parts[1], 10) || 0;
      const total = parseInt(parts[2], 10) || 0;
      setSyncProgress({ current, total });
      setSyncStatus('syncing');
    } else if (typeof msg === 'string' && msg.startsWith('sync_error')) {
      console.error('Sync error:', msg);
      finishSync('error');
    } else if (typeof msg === 'string' && msg.startsWith('urgent_emails:')) {
      const rest = msg.slice('urgent_emails:'.length);
      const sepIdx = rest.indexOf(':');
      const count = parseInt(rest.slice(0, sepIdx), 10) || 0;
      const subjects = sepIdx >= 0 ? rest.slice(sepIdx + 1) : '';
      setUrgentEmails({ count, subjects });
    }
  }, [finishSync]);

  useWebSocket(localStorage.getItem('accessToken'), {
    onNotifications,
    onChat,
    onConnect: () => setWsError(null),
    onError: () => setWsError('Conexión WebSocket perdida. Reintentando...'),
  });

  const autoSyncedRef = useRef(false);

  useEffect(() => {
    if (autoSyncedRef.current) return;
    const alreadySynced = sessionStorage.getItem('autoSynced');
    if (!alreadySynced && user) {
      sessionStorage.setItem('autoSynced', 'true');
      autoSyncedRef.current = true;
      const provider = user.provider;
      // Smart sync: check email count, auto-trigger full sync if empty
      api.emails.count().then(({ count }) => {
        if (count === 0) {
          handleSync(provider, 'full');
        } else {
          handleSync(provider, 'incremental');
        }
      }).catch(() => {
        handleSync(provider, 'incremental');
      });
    }
  }, [user, handleSync]);

  // Chat filters now navigate to the inbox, which renders the filtered emails.
  const handleChatFilters = useCallback((criteria) => {
    navigate('/inbox', { state: { criteria } });
  }, [navigate]);

  const isSyncing = syncStatus === 'syncing';
  const provider = user?.provider || 'google';

  return (
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      {wsError && (
        <div className="mb-4 flex items-center gap-2 rounded-lg bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-800 px-4 py-2 text-sm text-amber-700 dark:text-amber-300">
          <WifiOff className="w-4 h-4 flex-shrink-0" />
          <span>{wsError}</span>
        </div>
      )}

      {urgentEmails && (
        <div className="mb-4 flex items-center gap-2 rounded-lg bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 px-4 py-2 text-sm text-red-700 dark:text-red-300">
          <AlertTriangle className="w-4 h-4 flex-shrink-0" />
          <span className="flex-1 min-w-0">
            {urgentEmails.count} correo(s) urgente(s): {urgentEmails.subjects}
          </span>
          <button
            onClick={() => setUrgentEmails(null)}
            className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-800/50 transition-colors flex-shrink-0"
            title="Descartar"
            aria-label="Descartar aviso de correos urgentes"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      <div className="flex gap-6">
        <div className="flex-1 min-w-0 space-y-6">
          {/* Header */}
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">Dashboard</h1>
              <p className="text-sm text-gray-500 dark:text-gray-400 mt-0.5">
                Resumen de tu bandeja de entrada
              </p>
            </div>
            <div className="flex items-center gap-2">
              {/* Sync dropdown */}
              <div className="relative">
                <button
                  onClick={() => !isSyncing && setSyncMenuOpen(!syncMenuOpen)}
                  disabled={isSyncing}
                  className={`flex items-center gap-1.5 px-3 py-2 text-sm rounded-lg transition-colors ${
                    isSyncing
                      ? 'text-primary-700 dark:text-primary-300 bg-primary-50 dark:bg-primary-900/40 animate-pulse'
                      : syncStatus === 'success'
                      ? 'text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-900/30'
                      : syncStatus === 'error'
                      ? 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/30'
                      : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 border border-primary-200 dark:border-gray-600'
                  }`}
                >
                  {isSyncing ? (
                    <Loader2 className="w-4 h-4 animate-spin" />
                  ) : syncStatus === 'success' ? (
                    <Check className="w-4 h-4" />
                  ) : syncStatus === 'error' ? (
                    <AlertCircle className="w-4 h-4" />
                  ) : (
                    <RefreshCw className="w-4 h-4" />
                  )}
                  <span className="hidden sm:inline">
                    {isSyncing ? 'Sincronizando...' : 'Sincronizar'}
                  </span>
                  {!isSyncing && <ChevronDown className="w-3.5 h-3.5" />}
                </button>

                {syncMenuOpen && !isSyncing && (
                  <>
                    <div className="fixed inset-0 z-40" onClick={() => setSyncMenuOpen(false)} />
                    <div className="absolute right-0 mt-1 w-64 bg-white dark:bg-gray-800 border border-primary-200 dark:border-gray-600 rounded-lg shadow-lg z-50 overflow-hidden">
                      <button
                        onClick={() => { handleSync(provider, 'incremental'); setSyncMenuOpen(false); }}
                        className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors"
                      >
                        <Clock className="w-5 h-5 text-green-600 dark:text-green-400 flex-shrink-0" />
                        <div>
                          <div className="text-sm font-medium text-gray-900 dark:text-gray-100">Actualizar nuevos emails</div>
                          <div className="text-xs text-gray-500 dark:text-gray-400">Rápido, solo emails nuevos</div>
                        </div>
                      </button>
                      <div className="border-t border-gray-100 dark:border-gray-700" />
                      <button
                        onClick={() => { handleSync(provider, 'full'); setSyncMenuOpen(false); }}
                        className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors"
                      >
                        <Database className="w-5 h-5 text-primary-600 dark:text-primary-400 flex-shrink-0" />
                        <div>
                          <div className="text-sm font-medium text-gray-900 dark:text-gray-100">Descargar todo el historial</div>
                          <div className="text-xs text-gray-500 dark:text-gray-400">¿Primera vez? Haz clic aquí</div>
                        </div>
                      </button>
                    </div>
                  </>
                )}
              </div>

              <button
                onClick={fetchStats}
                disabled={loading}
                className="p-2 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                title="Actualizar estadísticas"
              >
                <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>

          {/* Sync progress */}
          {isSyncing && syncProgress.total > 0 && (
            <div className="card dark:bg-gray-800 dark:border-gray-700">
              <div className="flex items-center justify-between text-xs text-gray-500 dark:text-gray-400 mb-1">
                <span>
                  {syncMode === 'full' ? 'Descargando historial completo' : 'Sincronizando nuevos emails'}
                </span>
                <span>{syncProgress.current.toLocaleString()} / {syncProgress.total.toLocaleString()}</span>
              </div>
              <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2 overflow-hidden">
                <div
                  className="h-full bg-primary-600 rounded-full transition-all duration-300"
                  style={{ width: `${Math.min((syncProgress.current / syncProgress.total) * 100, 100)}%` }}
                />
              </div>
            </div>
          )}

          {/* Loading skeleton (first load only) */}
          {loading && !stats && (
            <LoadingState variant="skeleton" testId="dashboard-loading" className="space-y-6">
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="card animate-pulse dark:bg-gray-800 dark:border-gray-700">
                    <div className="flex items-center gap-4">
                      <div className="w-12 h-12 bg-gray-200 dark:bg-gray-700 rounded-xl" />
                      <div className="flex-1 space-y-2">
                        <div className="h-6 bg-gray-200 dark:bg-gray-700 rounded w-16" />
                        <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-24" />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
              <div className="card animate-pulse dark:bg-gray-800 dark:border-gray-700">
                <div className="h-5 bg-gray-200 dark:bg-gray-700 rounded w-48 mb-4" />
                <div className="h-40 bg-gray-200 dark:bg-gray-700 rounded" />
              </div>
            </LoadingState>
          )}

          {/* Error state (only when there is nothing to show) */}
          {error && !stats && !loading && (
            <ErrorState
              message="Error al cargar estadísticas"
              detail={error}
              onRetry={fetchStats}
              className="card dark:bg-gray-800 dark:border-gray-700"
            />
          )}

          {/* Stats */}
          {stats && (
            <>
              <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                {STAT_CARDS.map(({ key, label, icon, iconClass }) => (
                  <StatCard key={key} label={label} value={stats[key]} icon={icon} iconClass={iconClass} />
                ))}
              </div>

              <div className="card dark:bg-gray-800 dark:border-gray-700">
                <div className="flex items-center gap-2 mb-4">
                  <BarChart3 className="w-5 h-5 text-primary-600" />
                  <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                    Emails por día <span className="text-sm font-normal text-gray-500 dark:text-gray-400">(últimos 7 días)</span>
                  </h2>
                </div>
                <BarChart data={stats.perDay} />
              </div>

              <div className="card dark:bg-gray-800 dark:border-gray-700">
                <div className="flex items-center gap-2 mb-4">
                  <Sparkles className="w-5 h-5 text-accent-600" />
                  <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Resumen de hoy</h2>
                  <button
                    onClick={fetchSummary}
                    disabled={summaryLoading}
                    className="ml-auto p-1.5 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                    title="Actualizar resumen"
                    aria-label="Actualizar resumen"
                  >
                    <RefreshCw className={`w-4 h-4 ${summaryLoading ? 'animate-spin' : ''}`} />
                  </button>
                </div>

                {summaryLoading && !dailySummary ? (
                  <div className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400 py-4">
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>Generando resumen...</span>
                  </div>
                ) : summaryError ? (
                  <div className="flex items-center gap-2 text-sm text-red-600 dark:text-red-400 py-2">
                    <AlertCircle className="w-4 h-4 flex-shrink-0" />
                    <span className="flex-1 min-w-0">{summaryError}</span>
                    <button
                      onClick={fetchSummary}
                      className="px-2 py-1 text-xs font-medium rounded-lg border border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors flex-shrink-0"
                    >
                      Reintentar
                    </button>
                  </div>
                ) : dailySummary ? (
                  <>
                    <ReactMarkdown
                      remarkPlugins={[remarkGfm]}
                      components={{
                        h2: ({ children }) => (
                          <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100 mt-3 mb-1 first:mt-0">{children}</h2>
                        ),
                        h3: ({ children }) => (
                          <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100 mt-2 mb-1 first:mt-0">{children}</h3>
                        ),
                        p: ({ children }) => (
                          <p className="text-sm text-gray-700 dark:text-gray-300 my-1.5">{children}</p>
                        ),
                        strong: ({ children }) => (
                          <strong className="font-semibold text-gray-900 dark:text-gray-100">{children}</strong>
                        ),
                        em: ({ children }) => (
                          <em className="italic">{children}</em>
                        ),
                        ul: ({ children }) => (
                          <ul className="list-disc pl-5 my-1.5 space-y-0.5 text-sm text-gray-700 dark:text-gray-300">{children}</ul>
                        ),
                        ol: ({ children }) => (
                          <ol className="list-decimal pl-5 my-1.5 space-y-0.5 text-sm text-gray-700 dark:text-gray-300">{children}</ol>
                        ),
                        li: ({ children }) => <li className="my-0.5">{children}</li>,
                        table: ({ children }) => (
                          <div className="overflow-x-auto my-2">
                            <table className="w-full text-xs text-gray-700 dark:text-gray-300 border-collapse">{children}</table>
                          </div>
                        ),
                        thead: ({ children }) => <thead className="bg-gray-50 dark:bg-gray-700/50">{children}</thead>,
                        th: ({ children }) => (
                          <th className="px-2 py-1.5 text-left font-semibold text-gray-900 dark:text-gray-100 border border-gray-200 dark:border-gray-700">{children}</th>
                        ),
                        td: ({ children }) => (
                          <td className="px-2 py-1.5 border border-gray-200 dark:border-gray-700">{children}</td>
                        ),
                        hr: () => <hr className="my-3 border-gray-200 dark:border-gray-700" />,
                        a: ({ href, children }) => (
                          <a href={href} target="_blank" rel="noopener noreferrer" className="text-primary-700 dark:text-primary-400 hover:underline">{children}</a>
                        ),
                      }}
                    >
                      {dailySummary.summary}
                    </ReactMarkdown>
                    <div className="mt-3 text-xs text-gray-500 dark:text-gray-400">
                      {dailySummary.emailCount} correos · {format(new Date(dailySummary.date + 'T00:00:00'), 'dd/MM/yyyy')}
                    </div>
                  </>
                ) : null}
              </div>

              <div className="card dark:bg-gray-800 dark:border-gray-700">
                <div className="flex items-center gap-2 mb-4">
                  <Target className="w-5 h-5 text-primary-600" />
                  <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Resumen priorizado</h2>
                  <button
                    onClick={fetchPrioritySummary}
                    disabled={priorityLoading}
                    className="ml-auto p-1.5 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                    title="Actualizar resumen priorizado"
                    aria-label="Actualizar resumen priorizado"
                  >
                    <RefreshCw className={`w-4 h-4 ${priorityLoading ? 'animate-spin' : ''}`} />
                  </button>
                </div>

                {priorityLoading && !prioritySummary ? (
                  <div className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400 py-4">
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span>Calculando prioridades...</span>
                  </div>
                ) : priorityError ? (
                  <div className="flex items-center gap-2 text-sm text-red-600 dark:text-red-400 py-2">
                    <AlertCircle className="w-4 h-4 flex-shrink-0" />
                    <span className="flex-1 min-w-0">{priorityError}</span>
                    <button
                      onClick={fetchPrioritySummary}
                      className="px-2 py-1 text-xs font-medium rounded-lg border border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors flex-shrink-0"
                    >
                      Reintentar
                    </button>
                  </div>
                ) : prioritySummary ? (
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400">
                        <AlertTriangle className="w-5 h-5" />
                      </div>
                      <div className="min-w-0">
                        <div className="text-2xl font-bold text-gray-900 dark:text-gray-100">
                          {prioritySummary.urgentes.toLocaleString()}
                        </div>
                        <div className="text-sm text-gray-500 dark:text-gray-400 truncate">urgentes</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300">
                        <Star className="w-5 h-5" />
                      </div>
                      <div className="min-w-0">
                        <div className="text-2xl font-bold text-gray-900 dark:text-gray-100">
                          {prioritySummary.destacadosSinLeer.toLocaleString()}
                        </div>
                        <div className="text-sm text-gray-500 dark:text-gray-400 truncate">destacados sin leer</div>
                      </div>
                    </div>
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300">
                        <Clock className="w-5 h-5" />
                      </div>
                      <div className="min-w-0">
                        <div className="text-2xl font-bold text-gray-900 dark:text-gray-100">
                          {prioritySummary.pendientesRespuesta.toLocaleString()}
                        </div>
                        <div className="text-sm text-gray-500 dark:text-gray-400 truncate">esperando respuesta</div>
                      </div>
                    </div>
                  </div>
                ) : null}
              </div>
            </>
          )}
        </div>

        {/* Chat panel */}
        <div className="flex-shrink-0 hidden lg:block w-96">
          <div className="sticky top-20 h-[calc(100vh-6rem)]">
            <ChatPanel
              onFiltersApplied={handleChatFilters}
              chatResponse={chatResponse}
              onChatConsumed={() => setChatResponse(null)}
            />
          </div>
        </div>
      </div>
    </div>
  );
}