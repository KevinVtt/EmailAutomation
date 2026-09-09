import { Loader2 } from 'lucide-react';

/**
 * Shared loading state. Two variants:
 * - `spinner`: a centered spinner with an optional label (default).
 * - `skeleton`: a list of skeleton rows (used by email lists / cards).
 */
export default function LoadingState({
  variant = 'spinner',
  label = 'Cargando...',
  rows = 5,
  compact = false,
  className = '',
  testId,
  children,
}) {
  if (variant === 'skeleton') {
    return (
      <div data-testid={testId} className={`space-y-2 ${className}`}>
        {children ??
          Array.from({ length: rows }).map((_, i) => (
            <div key={i} className="animate-pulse flex gap-3 p-3 rounded-lg">
              <div className="w-10 h-10 bg-gray-200 dark:bg-gray-700 rounded-full" />
              <div className="flex-1 space-y-2">
                <div className="h-4 bg-gray-200 dark:bg-gray-700 rounded w-1/3" />
                <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded w-2/3" />
              </div>
            </div>
          ))}
      </div>
    );
  }

  return (
    <div
      data-testid={testId}
      className={`flex flex-col items-center justify-center text-gray-400 dark:text-gray-500 ${compact ? 'py-4' : 'py-12'} ${className}`}
    >
      <Loader2 className="w-8 h-8 animate-spin mb-3" />
      <p className="text-sm">{label}</p>
    </div>
  );
}