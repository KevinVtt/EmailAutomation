import { useState, useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useWebSocket } from '../hooks/useWebSocket';
import { api } from '../lib/api';
import ChatPanel from '../components/ChatPanel';
import { LoadingState, ErrorState } from '../components/states';
import { format } from 'date-fns';
import {
  Mail, MailOpen, Star, ShieldAlert, RefreshCw, Loader2, AlertCircle,
  Check, Clock, Database, ChevronDown, WifiOff, BarChart3,
} from 'lucide-react';

const STAT_CARDS = [
  { key: 'total', label: 'Total de emails', icon: Mail, iconClass: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400' },
  { key: 'unread', label: 'No leídos', icon: MailOpen, iconClass: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400' },
  { key: 'starred', label: 'Destacados', icon: Star, iconClass: 'bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400' },
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
          <span className="text-xs text-gray-400 dark:text-gray-500">{d.label}</span>
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

  const fetchStats = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [countRes, unreadRes, starredRes, spamRes] = await Promise.all([
        api.emails.count(),
        api.emails.filter({ isRead: 'false' }, 0, 1),
        api.emails.filter({ isStarred: 'true' }, 0, 1),
        api.emails.filter({ label: 'SPAM' }, 0, 1),
      ]);

      // Emails per day for the last 7 days (local midnight → next midnight, minus 1ms).
      const dayQueries = [];
      for (let i = 6; i >= 0; i--) {
        const dayStart = new Date();
        dayStart.setHours(0, 0, 0, 0);
        dayStart.setDate(dayStart.getDate() - i);
        const dayEnd = new Date(dayStart);
        dayEnd.setDate(dayEnd.getDate() + 1);
        dayEnd.setMilliseconds(dayEnd.getMilliseconds() - 1);
        dayQueries.push({
          label: format(dayStart, 'dd/MM'),
          promise: api.emails.filter({
            dateFrom: dayStart.toISOString(),
            dateTo: dayEnd.toISOString(),
          }, 0, 1),
        });
      }
      const dayResults = await Promise.all(dayQueries.map((q) => q.promise));

      setStats({
        total: countRes.count || 0,
        unread: unreadRes.totalElements || 0,
        starred: starredRes.totalElements || 0,
        spam: spamRes.totalElements || 0,
        perDay: dayQueries.map((q, i) => ({
          label: q.label,
          count: dayResults[i].totalElements || 0,
        })),
      });
    } catch (err) {
      console.error('Failed to fetch stats', err);
      setError(err.message || 'Error al cargar estadísticas');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

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
      const pollInterval = setInterval(async () => {
        try {
          const status = await api.emails.syncStatus();
          if (!status.syncing) {
            clearInterval(pollInterval);
            setSyncStatus('success');
            setSyncMode(null);
            setTimeout(() => { setSyncStatus(null); setSyncProgress({ current: 0, total: 0 }); }, 3000);
            fetchStats();
          }
        } catch {
          // Ignore poll errors
        }
      }, 2000);
      // Safety: stop polling after 10 minutes for full sync, 3 minutes for incremental
      const timeout = mode === 'full' ? 600000 : 180000;
      setTimeout(() => clearInterval(pollInterval), timeout);
    } catch (err) {
      console.error('Failed to trigger sync', err);
      setSyncStatus('error');
      setSyncMode(null);
      setTimeout(() => { setSyncStatus(null); setSyncProgress({ current: 0, total: 0 }); }, 5000);
    }
  }, [fetchStats]);

  const onChat = useCallback((data) => {
    if (data.type === 'chat_response' && data.data) {
      setChatResponse(data.data);
    }
  }, []);

  const onNotifications = useCallback((data) => {
    if (data.type !== 'notification') return;

    const msg = data.message;
    if (msg === 'sync_complete') {
      setSyncStatus('success');
      setSyncMode(null);
      setTimeout(() => { setSyncStatus(null); setSyncProgress({ current: 0, total: 0 }); }, 3000);
      fetchStats();
    } else if (typeof msg === 'string' && msg.startsWith('sync_progress:')) {
      // sync_progress:current:total
      const parts = msg.split(':');
      const current = parseInt(parts[1], 10) || 0;
      const total = parseInt(parts[2], 10) || 0;
      setSyncProgress({ current, total });
      setSyncStatus('syncing');
    } else if (typeof msg === 'string' && msg.startsWith('sync_error')) {
      console.error('Sync error:', msg);
      setSyncStatus('error');
      setSyncMode(null);
      setTimeout(() => { setSyncStatus(null); setSyncProgress({ current: 0, total: 0 }); }, 5000);
    }
  }, [fetchStats]);

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
                      ? 'text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/40 animate-pulse'
                      : syncStatus === 'success'
                      ? 'text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-900/30'
                      : syncStatus === 'error'
                      ? 'text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/30'
                      : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 border border-gray-200 dark:border-gray-600'
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
                    <div className="absolute right-0 mt-1 w-64 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-600 rounded-lg shadow-lg z-50 overflow-hidden">
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
                        <Database className="w-5 h-5 text-purple-600 dark:text-purple-400 flex-shrink-0" />
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
                  className="h-full bg-gradient-to-r from-blue-500 to-purple-500 rounded-full transition-all duration-300"
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