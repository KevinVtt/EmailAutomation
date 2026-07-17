import { RefreshCw, Inbox, Download, ChevronLeft, ChevronRight, X, AlertCircle, Check, Loader2 } from 'lucide-react';
import EmailCard from './EmailCard';

export default function EmailList({
  emails, loading, error, syncStatus, selectedId, onSelect, onRefresh, onSync,
  page, totalPages, onPageChange,
  dateFrom, dateTo, onDateFromChange, onDateToChange, onClearDates
}) {
  return (
    <div className="card flex flex-col h-full">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-lg font-semibold text-gray-900">Bandeja de entrada</h2>
        <div className="flex gap-1">
          <button
            onClick={() => onSync?.('google')}
            disabled={loading || syncStatus === 'syncing'}
            className={`p-2 rounded-lg transition-colors ${
              syncStatus === 'syncing'
                ? 'text-blue-600 bg-blue-50 animate-pulse'
                : syncStatus === 'success'
                ? 'text-green-600 bg-green-50'
                : syncStatus === 'error'
                ? 'text-red-600 bg-red-50'
                : 'text-gray-500 hover:text-blue-600 hover:bg-blue-50'
            }`}
            title={
              syncStatus === 'syncing'
                ? 'Sincronizando...'
                : syncStatus === 'success'
                ? 'Sincronizado correctamente'
                : syncStatus === 'error'
                ? 'Error al sincronizar'
                : 'Sincronizar Gmail'
            }
          >
            {syncStatus === 'syncing' ? (
              <Loader2 className="w-5 h-5 animate-spin" />
            ) : syncStatus === 'success' ? (
              <Check className="w-5 h-5" />
            ) : syncStatus === 'error' ? (
              <AlertCircle className="w-5 h-5" />
            ) : (
              <Download className="w-5 h-5" />
            )}
          </button>
          <button
            onClick={onRefresh}
            disabled={loading}
            className="p-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <RefreshCw className={`w-5 h-5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
      </div>

      <div className="flex items-center gap-2 mb-3 pb-3 border-b border-gray-100">
        <div className="flex items-center gap-1">
          <label className="text-xs text-gray-500">Desde:</label>
          <input
            type="date"
            value={dateFrom || ''}
            onChange={(e) => onDateFromChange(e.target.value || null)}
            className="text-xs border border-gray-200 rounded px-2 py-1 w-32 focus:outline-none focus:border-primary-400"
          />
        </div>
        <div className="flex items-center gap-1">
          <label className="text-xs text-gray-500">Hasta:</label>
          <input
            type="date"
            value={dateTo || ''}
            onChange={(e) => onDateToChange(e.target.value || null)}
            className="text-xs border border-gray-200 rounded px-2 py-1 w-32 focus:outline-none focus:border-primary-400"
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

      <div className="flex-1 overflow-y-auto space-y-2 -mx-4 px-4">
        {loading ? (
          Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="animate-pulse flex gap-3 p-3 rounded-lg">
              <div className="w-10 h-10 bg-gray-200 rounded-full" />
              <div className="flex-1 space-y-2">
                <div className="h-4 bg-gray-200 rounded w-1/3" />
                <div className="h-3 bg-gray-200 rounded w-2/3" />
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
              className="mt-3 px-3 py-1 text-xs bg-red-50 text-red-600 rounded hover:bg-red-100 transition-colors"
            >
              Reintentar
            </button>
          </div>
        ) : emails.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-gray-400">
            <Inbox className="w-12 h-12 mb-3" />
            <p className="text-sm">No hay emails que mostrar</p>
            <p className="text-xs mt-1">Prueba a hacer una búsqueda con la IA</p>
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

      <div className="flex items-center justify-between pt-3 mt-3 border-t border-gray-100">
        <div className="text-xs text-gray-400">
          {emails.length} emails {totalPages > 1 && `- Pág ${page + 1} de ${totalPages}`}
        </div>
        {totalPages > 1 && (
          <div className="flex gap-1">
            <button
              onClick={() => onPageChange(page - 1)}
              disabled={page === 0 || loading}
              className="p-1 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <button
              onClick={() => onPageChange(page + 1)}
              disabled={page >= totalPages - 1 || loading}
              className="p-1 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
