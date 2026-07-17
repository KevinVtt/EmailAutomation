import { Star, Mail, MailOpen } from 'lucide-react';
import { formatDistanceToNow } from 'date-fns';
import { es } from 'date-fns/locale';
import clsx from 'clsx';

export default function EmailCard({ email, selected, onSelect }) {
  const initials = (email.fromName || email.fromAddress || '?')
    .charAt(0)
    .toUpperCase();

  return (
    <button
      onClick={onSelect}
      className={clsx(
        'w-full text-left p-3 rounded-lg transition-colors duration-150 flex gap-3',
        selected
          ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-200 dark:border-primary-800'
          : 'hover:bg-gray-50 dark:hover:bg-gray-700 border border-transparent'
      )}
    >
      <div className="flex-shrink-0">
        <div
          className={clsx(
            'w-10 h-10 rounded-full flex items-center justify-center text-sm font-medium text-white',
            email.isRead ? 'bg-gray-400 dark:bg-gray-600' : 'bg-primary-500'
          )}
        >
          {initials}
        </div>
      </div>

      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between gap-2">
          <span
            className={clsx(
              'text-sm truncate',
              email.isRead
                ? 'text-gray-600 dark:text-gray-400'
                : 'text-gray-900 dark:text-gray-100 font-semibold'
            )}
          >
            {email.fromName || email.fromAddress || 'Unknown'}
          </span>
          <div className="flex items-center gap-1 flex-shrink-0">
            {email.isStarred && <Star className="w-3.5 h-3.5 text-yellow-400 fill-yellow-400" />}
            {!email.isRead ? (
              <Mail className="w-3.5 h-3.5 text-primary-500" />
            ) : (
              <MailOpen className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500" />
            )}
          </div>
        </div>

        <p
          className={clsx(
            'text-sm truncate mt-0.5',
            email.isRead
              ? 'text-gray-500 dark:text-gray-400'
              : 'text-gray-700 dark:text-gray-200 font-medium'
          )}
        >
          {email.subject || '(Sin asunto)'}
        </p>

        <p className="text-xs text-gray-400 dark:text-gray-500 truncate mt-0.5">
          {email.bodyPreview || ''}
        </p>

        <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
          {email.receivedAt
            ? formatDistanceToNow(new Date(email.receivedAt), {
                addSuffix: true,
                locale: es,
              })
            : ''}
        </p>
      </div>
    </button>
  );
}
