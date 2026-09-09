import { Inbox } from 'lucide-react';

/**
 * Shared empty state: an icon + message, optionally with a hint and an action.
 */
export default function EmptyState({
  icon: Icon = Inbox,
  message = 'No hay elementos para mostrar',
  hint,
  action,
  className = '',
  testId,
}) {
  return (
    <div
      data-testid={testId}
      className={`flex flex-col items-center justify-center py-12 text-gray-400 dark:text-gray-500 ${className}`}
    >
      <Icon className="w-12 h-12 mb-3" />
      <p className="text-sm font-medium">{message}</p>
      {hint && <p className="text-xs mt-1 text-center max-w-xs">{hint}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}