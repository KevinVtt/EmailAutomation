import { useState, useRef } from 'react';
import { RefreshCw, Inbox, ChevronLeft, ChevronRight, X, AlertCircle, Check, Loader2, Database, Clock, ChevronDown } from 'lucide-react';
import EmailCard from './EmailCard';

export default function EmailList({
  emails, loading, error, syncStatus, syncProgress, syncMode, selectedId, onSelect, onRefresh, onSync,
  page, totalPages, onPageChange,
  dateFrom, dateTo, onDateFromChange, onDateToChange, onClearDates,
  emailCount
}) {
  const [syncMenuOpen, setSyncMenuOpen] = useState(false);
  const [bannerDismissed, setBannerDismissed] = useState(false);
  const menuRef = useRef(null);

  const isSyncing = syncStatus === 'syncing';

  return (
    <div className="card flex flex-col h-full dark:bg-gray-800 dark:border-gray-700">
      {/* Sync banner */}
      {emailCount > 0 && !bannerDismissed && (
        <div className="mb-3 flex items-center justify-between rounded-lg bg-blue-50 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800 px-3 py-2 text-sm text-blue-700 dark:text-blue-300">
          <span>¿Quieres ver todos tus emails? Sincroniza el historial completo.</span>
          <div className="flex items-center gap-1 flex-shrink-0">
            <button
              onClick={() => { onSync?.('google', 'full'); setSyncMenuOpen(false); }}
              className="text-xs font-medium px-2 py-1 rounded bg-blue-600 text-white hover:bg-blue-700 transition-colors"
            >
              Sincronizar todo
            </button>
            <button
              onClick={() => setBannerDismissed(true)}
              className="p-1 text-blue-400 hover:text-blue-600 rounded transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      )}

      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Bandeja de entrada</h2>
        <div className="flex gap-1">
          {/* Sync dropdown button */}
          <div className="relative" ref={menuRef}>
            <button
              onClick={() => !isSyncing && setSyncMenuOpen(!syncMenuOpen)}
              disabled={loading || isSyncing}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-sm rounded-lg transition-colors ${
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
                    onClick={() => { onSync?.('google', 'incremental'); setSyncMenuOpen(false); }}
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
                    onClick={() => { onSync?.('google', 'full'); setSyncMenuOpen(false); }}
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
            onClick={onRefresh}
            disabled={loading}
            className="p-2 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
          >
            <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      <div className="flex items-center gap-2 mb-3 pb-3 border-b border-gray-100 dark:border-gray-700">
        <div className="flex items-center gap-1">
          <label className="text-xs text-gray-500 dark:text-gray-400">Desde:</label>
          <input
            type="date"
            value={dateFrom || ''}
            onChange={(e) => onDateFromChange(e.target.value || null)}
            className="text-xs border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 rounded px-2 py-1 w-32 focus:outline-none focus:border-primary-400"
          />
        </div>
        <div className="flex items-center gap-1">
          <label className="text-xs text-gray-500 dark:text-gray-400">Hasta:</label>
          <input
            type="date"
            value={dateTo || ''}
            onChange={(e) => onDateToChange(e.target.value || null)}
            className="text-xs border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 rounded px-2 py-1 w-32 focus:outline-none focus:border-primary-400"
          />
        </div>
        {(dateFrom || dateTo) && (
          <button
            onClick={onClearDates}
            className="p-1 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded transition-colors"
            title="Limpiar filtros"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {isSyncing && syncProgress.total > 0 && (
        <div className="mb-3 px-1">
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
          <div className="text-xs text-gray-400 dark:text-gray-500 mt-1 text-center">
            Esto puede tardar unos minutos para buzones grandes...
          </div>
        </div>
      )}

      <div className="flex-1 overflow-y-auto space-y-2 -mx-4 px-4">
        {loading ? (
          Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="animate-pulse flex gap-3 p-3 rounded-lg">
              <div className="w-10 h-10 bg-gray-200 dark:bg-gray-700 rounded-full" />
              <div className="flex-1 space-y-2">
                <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/3" />
                <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-2/3" />
              </div>
            </div>
          ))
        ) : error ? (
          <div className="flex flex-col items-center justify-center py-12 text-red-400">
            <AlertCircle className="w-12 h-12 mb-3" />
            <p className="text-sm">Error al cargar emails</p>
            <p className="text-xs mt-1">{error}</p>
            <button
              onClick={onRefresh}
              className="mt-3 px-3 py-1 text-xs bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400 rounded hover:bg-red-100 dark:hover:bg-red-900/50 transition-colors"
            >
              Reintentar
            </button>
          </div>
        ) : emails.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-gray-400 dark:text-gray-500">
            <Inbox className="w-12 h-12 mb-3" />
            <p className="text-sm font-medium">Tus emails aparecerán aquí</p>
            <p className="text-xs mt-1 text-center max-w-xs">La primera sincronización puede tardar unos minutos.</p>
          </div>
        ) : (
          emails.map((email) => (
            <EmailCard
              key={email.id}
              email={email}
              selected={email.id === selectedId}
              onSelect={() => onSelect(email)}
            />
          ))
        )}
      </div>

      <div className="flex items-center justify-between pt-3 mt-3 border-t border-gray-100 dark:border-gray-700">
        <div className="text-xs text-gray-400 dark:text-gray-500">
          {emails.length} emails {totalPages > 1 && `- Pág ${page + 1} de ${totalPages}`}
        </div>
        {totalPages > 1 && (
          <div className="flex gap-1">
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0 || loading}
              className="p-1 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={page >= totalPages - 1 || loading}
              className="p-1 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
