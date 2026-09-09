import { AlertCircle } from 'lucide-react';

/**
 * Shared error state with a retry button.
 * `message` is the human-readable title; `detail` is the raw error detail.
 */
export default function ErrorState({
  message = 'Error al cargar los datos',
  detail,
  onRetry,
  retryLabel = 'Reintentar',
  className = '',
  testId,
}) {
  return (
    <div
      data-testid={testId}
      className={`flex flex-col items-center justify-center py-12 text-red-400 ${className}`}
    >
      <AlertCircle className="w-12 h-12 mb-3" />
      <p className="text-sm font-medium">{message}</p>
      {detail && <p className="text-xs mt-1 text-center max-w-xs">{detail}</p>}
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-3 px-3 py-1 text-xs bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400 rounded hover:bg-red-100 dark:hover:bg-red-900/50 transition-colors"
        >
          {retryLabel}
        </button>
      )}
    </div>
  );
}