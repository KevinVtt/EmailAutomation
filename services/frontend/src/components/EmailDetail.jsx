import { useState, useEffect, useCallback } from 'react';
import { X, Star, Mail, MailOpen, Trash2, ArrowLeft, Loader2, Reply } from 'lucide-react';
import { format } from 'date-fns';
import { es } from 'date-fns/locale';
import { api } from '../lib/api';
import ReplyPanel from './ReplyPanel';

const LABEL_MAP = {
  CATEGORY_PROMOTIONS: 'Promociones',
  CATEGORY_SOCIAL: 'Social',
  CATEGORY_UPDATES: 'Actualizaciones',
  CATEGORY_FORUMS: 'Foros',
  CATEGORY_PRIMARY: 'Principal',
  SPAM: 'No deseado',
  IMPORTANT: 'Importante',
  INBOX: 'Bandeja',
  UNREAD: 'Sin leer',
  STARRED: 'Favorito',
  SENT: 'Enviados',
  DRAFT: 'Borrador',
  TRASH: 'Papelera',
};

function formatLabel(label) {
  const trimmed = label.trim();
  return LABEL_MAP[trimmed] || trimmed.charAt(0).toUpperCase() + trimmed.slice(1).toLowerCase();
}

export default function EmailDetail({ email, onClose, onAction, onRead }) {
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(null);
  const [showReply, setShowReply] = useState(false);

  useEffect(() => {
    if (!email) return;
    setLoading(true);
    setShowReply(false);
    api.emails.get(email.id)
      .then((data) => {
        setDetail(data);
        // Auto-mark as read when opened (via visto table)
        if (!data.visto && email.provider && email.providerEmailId) {
          api.emails.action(email.provider, email.providerEmailId, 'read')
            .then(() => {
              setDetail(prev => prev ? { ...prev, visto: true } : prev);
              onRead?.(email.id);
            })
            .catch(console.error);
        }
      })
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
        setDetail(prev => prev ? { ...prev, visto: true } : prev);
      } else if (actionType === 'unread') {
        setDetail(prev => prev ? { ...prev, visto: false } : prev);
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
    <div className="card flex flex-col h-full dark:bg-gray-800 dark:border-gray-700">
      <div className="flex items-center justify-between mb-4 pb-3 border-b border-gray-200 dark:border-gray-700">
        <div className="flex items-center gap-1">
          {showReply ? (
            <button
              onClick={() => setShowReply(false)}
              className="p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
              title="Volver al email"
            >
              <ArrowLeft className="w-5 h-5 text-gray-500 dark:text-gray-400" />
            </button>
          ) : (
            <button onClick={onClose} className="p-1.5 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors">
              <ArrowLeft className="w-5 h-5 text-gray-500 dark:text-gray-400" />
            </button>
          )}
        </div>
        <div className="flex gap-1 flex-wrap">
          {email.labels?.split(',').filter(l => !['INBOX', 'UNREAD', 'CATEGORY_PRIMARY'].includes(l)).map((label, i) => (
            <span key={i} className="text-xs px-2 py-1 rounded-full bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 font-medium">
              {formatLabel(label)}
            </span>
          ))}
          {(!email.labels || email.labels.split(',').filter(l => !['INBOX', 'UNREAD', 'CATEGORY_PRIMARY'].includes(l)).length === 0) && (
            <span className="text-xs px-2 py-1 rounded-full bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300 font-medium">
              Bandeja
            </span>
          )}
        </div>
      </div>

      {loading ? (
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="w-6 h-6 text-primary-500 animate-spin" />
        </div>
      ) : showReply ? (
        <div className="flex-1 overflow-y-auto -mx-4 px-4">
          <ReplyPanel email={{ ...email, ...detail }} onClose={() => setShowReply(false)} />
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto">
          {/* Subject */}
          <h2 className="text-xl font-normal text-gray-900 dark:text-gray-100 mb-4 leading-snug">
            {detail?.subject || email.subject}
          </h2>

          {/* Action bar — Gmail style */}
          <div className="flex items-center gap-1 mb-4 pb-3 border-b border-gray-200 dark:border-gray-700">
            <button
              onClick={() => setShowReply(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-sm text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors"
            >
              <Reply className="w-4 h-4" />
              <span>Responder</span>
            </button>
            <button
              onClick={() => handleAction(detail?.isStarred ? 'unstar' : 'star')}
              disabled={actionLoading === 'star' || actionLoading === 'unstar'}
              className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-yellow-500 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
              title={detail?.isStarred ? 'Quitar estrella' : 'Destacar'}
            >
              <Star className={`w-4 h-4 ${detail?.isStarred ? 'fill-yellow-400 text-yellow-400' : ''}`} />
            </button>
            <button
              onClick={() => handleAction(detail?.visto ? 'unread' : 'read')}
              disabled={actionLoading === 'read' || actionLoading === 'unread'}
              className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-blue-600 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
              title={detail?.visto ? 'Marcar como no leído' : 'Marcar como leído'}
            >
              {detail?.visto ? <Mail className="w-4 h-4" /> : <MailOpen className="w-4 h-4" />}
            </button>
            <button
              onClick={() => handleAction('trash')}
              disabled={actionLoading === 'trash'}
              className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-red-500 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
              title="Eliminar"
            >
              <Trash2 className="w-4 h-4" />
            </button>
          </div>

          {/* Sender card — Gmail style */}
          <div className="flex items-start gap-3 mb-6">
            <div className="w-10 h-10 rounded-full bg-primary-600 flex items-center justify-center text-sm font-medium text-white flex-shrink-0 mt-0.5">
              {(email.fromName || email.fromAddress || '?').charAt(0).toUpperCase()}
            </div>
            <div className="flex-1 min-w-0">
              <div className="flex items-baseline justify-between gap-2">
                <div className="min-w-0">
                  <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                    {email.fromName || email.fromAddress || 'Desconocido'}
                  </span>
                  <span className="text-xs text-gray-500 dark:text-gray-400 ml-2">
                    &lt;{email.fromAddress}&gt;
                  </span>
                </div>
                <span className="text-xs text-gray-500 dark:text-gray-400 flex-shrink-0">
                  {detail?.receivedAt
                    ? format(new Date(detail.receivedAt), "d 'de' MMMM 'de' yyyy, HH:mm", { locale: es })
                    : ''}
                </span>
              </div>
              {email.toAddresses && (
                <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">
                  Para: {email.toAddresses}
                </p>
              )}
              {/* Labels */}
              <div className="flex gap-1.5 mt-1.5 flex-wrap">
                {email.labels?.split(',').filter(l => !['INBOX', 'UNREAD', 'CATEGORY_PRIMARY'].includes(l)).map((label, i) => (
                  <span key={i} className="text-[11px] px-2 py-0.5 rounded bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 font-medium">
                    {formatLabel(label)}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* Email body */}
          <div className="text-[15px] leading-relaxed text-gray-800 dark:text-gray-200">
            {detail?.bodyHtml ? (
              <div
                className="email-body"
                dangerouslySetInnerHTML={{
                  __html: detail.bodyHtml
                    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
                    .replace(/on\w+="[^"]*"/gi, '')
                    .replace(/on\w+='[^']*'/gi, '')
                }}
              />
            ) : (
              <p className="text-gray-500 dark:text-gray-400 italic whitespace-pre-wrap">
                {detail?.bodyPreview || 'Sin contenido visible'}
              </p>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
