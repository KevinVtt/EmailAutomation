import { useState, useEffect, useCallback } from 'react';
import { X, Star, Mail, MailOpen, Trash2, ArrowLeft, Loader2 } from 'lucide-react';
import { format } from 'date-fns';
import { es } from 'date-fns/locale';
import { api } from '../lib/api';

export default function EmailDetail({ email, onClose, onAction }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(null);

  useEffect(() => {
    if (!email) return;
    setLoading(true);
    api.emails.get(email.id)
      .then(setDetail)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [email?.id]);

  const handleAction = useCallback(async (actionType) => {
    if (!email || actionLoading) return;
    setActionLoading(actionType);
    try {
      await api.emails.action(email.provider, email.providerEmailId, actionType);
      if (actionType === 'star') {
        setDetail(prev => prev ? { ...prev, isStarred: true } : prev);
      } else if (actionType === 'unstar') {
        setDetail(prev => prev ? { ...prev, isStarred: false } : prev);
      } else if (actionType === 'read') {
        setDetail(prev => prev ? { ...prev, isRead: true } : prev);
      } else if (actionType === 'unread') {
        setDetail(prev => prev ? { ...prev, isRead: false } : prev);
      } else if (actionType === 'trash') {
        onAction?.('trash');
        onClose?.();
      }
    } catch (err) {
      console.error('Action failed:', err);
    } finally {
      setActionLoading(null);
    }
  }, [email, actionLoading, onAction, onClose]);

  if (!email) return null;

  return (
    <div className="card flex flex-col h-full">
      <div className="flex items-center justify-between mb-4 pb-3 border-b border-gray-200">
        <button onClick={onClose} className="p-1.5 hover:bg-gray-100 rounded-lg transition-colors">
          <ArrowLeft className="w-5 h-5 text-gray-500" />
        </button>
        <div className="flex gap-1">
          <span className="text-xs px-2 py-1 rounded-full bg-blue-100 text-blue-700 font-medium">
            {email.labels?.split(',').filter(l => !['INBOX', 'UNREAD', 'CATEGORY_PRIMARY'].includes(l)).join(', ') || 'INBOX'}
          </span>
        </div>
      </div>

      {loading ? (
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto -mx-4 px-4">
          <h3 className="text-lg font-semibold text-gray-900 mb-2">
            {detail?.subject || email.subject}
          </h3>

          <div className="flex items-start gap-3 mb-4 p-3 bg-gray-50 rounded-lg">
            <div className="w-10 h-10 rounded-full bg-primary-500 flex items-center justify-center text-sm font-medium text-white flex-shrink-0">
              {(email.fromName || email.fromAddress || '?').charAt(0).toUpperCase()}
            </div>
            <div className="min-w-0">
              <p className="text-sm font-medium text-gray-900">
                {email.fromName || email.fromAddress || 'Unknown'}
              </p>
              <p className="text-xs text-gray-500">{email.fromAddress}</p>
              <p className="text-xs text-gray-400">
                {detail?.receivedAt
                  ? format(new Date(detail.receivedAt), "d 'de' MMMM 'de' yyyy, HH:mm", { locale: es })
                  : 'Fecha no disponible'}
              </p>
              {email.toAddresses && (
                <p className="text-xs text-gray-400 mt-1">Para: {email.toAddresses}</p>
              )}
            </div>
          </div>

          <div className="flex gap-2 mb-4">
            <button
              onClick={() => handleAction(detail?.isStarred ? 'unstar' : 'star')}
              disabled={actionLoading === 'star' || actionLoading === 'unstar'}
              className="p-1.5 text-gray-400 hover:text-yellow-500 hover:bg-yellow-50 rounded-lg transition-colors disabled:opacity-50"
              title={detail?.isStarred ? 'Unstar' : 'Star'}
            >
              <Star className={`w-4 h-4 ${detail?.isStarred ? 'fill-yellow-400 text-yellow-400' : ''}`} />
            </button>
            <button
              onClick={() => handleAction(detail?.isRead ? 'unread' : 'read')}
              disabled={actionLoading === 'read' || actionLoading === 'unread'}
              className="p-1.5 text-gray-400 hover:text-blue-600 hover:bg-blue-50 rounded-lg transition-colors disabled:opacity-50"
              title={detail?.isRead ? 'Mark unread' : 'Mark read'}
            >
              {detail?.isRead ? <Mail className="w-4 h-4" /> : <MailOpen className="w-4 h-4" />}
            </button>
            <button
              onClick={() => handleAction('trash')}
              disabled={actionLoading === 'trash'}
              className="p-1.5 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-50"
              title="Delete"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          </div>

          <div className="prose prose-sm max-w-none text-gray-700">
            {detail?.bodyHtml ? (
              <div
                dangerouslySetInnerHTML={{
                  __html: detail.bodyHtml
                    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
                    .replace(/on\w+="[^"]*"/gi, '')
                    .replace(/on\w+='[^']*'/gi, '')
                }}
              />
            ) : (
              <p className="text-gray-500 italic">Sin contenido visible</p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
