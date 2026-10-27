import { useState, useEffect, useCallback } from 'react';
import { X, Star, Mail, MailOpen, Trash2, ArrowLeft, Loader2, Reply, Paperclip, RotateCcw } from 'lucide-react';
import { format } from 'date-fns';
import { es } from 'date-fns/locale';
import { api } from '../lib/api';
import ReplyPanel from './ReplyPanel';
import EmailBodyIframe from './EmailBodyIframe';

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

function parseAttachments(json) {
  if (!json) return [];
  try {
    const list = JSON.parse(json);
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function formatSize(bytes) {
  if (!bytes) return '';
  return bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

export default function EmailDetail({ email, onClose, onAction, onRead, readOnly = false }) {
  const inTrash = (email?.labels || '').split(',').includes('TRASH');
  const inSpam = (email?.labels || '').split(',').includes('SPAM');
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(null);
  const [showReply, setShowReply] = useState(false);
  const [downloading, setDownloading] = useState(null);
  const [downloadError, setDownloadError] = useState('');

  const attachments = parseAttachments(detail?.attachmentsJson);

  const handleDownload = useCallback(async (index, filename) => {
    setDownloading(index);
    setDownloadError('');
    try {
      const blob = await api.emails.attachment(email.id, index);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename || 'adjunto';
      link.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setDownloadError(e.message || 'No se pudo descargar el adjunto');
    } finally {
      setDownloading(null);
    }
  }, [email?.id]);

  useEffect(() => {
    if (!email) return;
    // Enviados: el mail ya viene completo en la lista y no existe en /api/emails/{id}.
    if (readOnly) {
      setDetail(email);
      setLoading(false);
      return;
    }
    let alive = true;
    setLoading(true);
    setShowReply(false);
    api.emails.get(email.id)
      .then((data) => {
        if (alive) setDetail(data);
        // Auto-mark as read when opened (via visto table)
        if (!data.visto && email.provider && email.providerEmailId) {
          api.emails.action(email.provider, email.providerEmailId, 'read')
            .then(() => {
              if (!alive) return;
              setDetail(prev => prev ? { ...prev, visto: true } : prev);
              onRead?.(email.id);
            })
            .catch(console.error);
        }
      })
      .catch(console.error)
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [email?.id, readOnly]);

  const handleAction = useCallback(async (actionType) => {
    if (!email || actionLoading) return;
    if (actionType === 'delete' && !window.confirm('¿Eliminar definitivamente este mail? No se puede deshacer.')) return;
    if (['trash', 'restore', 'delete', 'unspam'].includes(actionType)) {
      // Optimista: la lista y el detalle reaccionan ya; si el proveedor falla, se avisa y se revierte.
      onAction?.(actionType);
      onClose?.();
      api.emails.action(email.provider, email.providerEmailId, actionType)
        .catch((err) => {
          console.error('Action failed:', err);
          onAction?.(`${actionType}:failed`);
        });
      return;
    }
    setActionLoading(actionType);
    try {
      await api.emails.action(email.provider, email.providerEmailId, actionType);
      if (actionType === 'star' || actionType === 'unstar') {
        // Lo que diga el servidor, no una suposición: si Gmail rechazó el cambio, acá se ve.
        const fresh = await api.emails.get(email.id);
        setDetail(fresh);
      } else if (actionType === 'read') {
        setDetail(prev => prev ? { ...prev, visto: true } : prev);
      } else if (actionType === 'unread') {
        setDetail(prev => prev ? { ...prev, visto: false } : prev);
      } else if (['trash', 'restore', 'delete'].includes(actionType)) {
        onAction?.(actionType);
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
            <span key={i} className="text-xs px-2 py-1 rounded-full bg-primary-100 dark:bg-primary-900/40 text-primary-800 dark:text-primary-300 font-medium">
              {formatLabel(label)}
            </span>
          ))}
          {(!email.labels || email.labels.split(',').filter(l => !['INBOX', 'UNREAD', 'CATEGORY_PRIMARY'].includes(l)).length === 0) && !readOnly && (
            <span className="text-xs px-2 py-1 rounded-full bg-primary-100 dark:bg-primary-900/40 text-primary-800 dark:text-primary-300 font-medium">
              Bandeja
            </span>
          )}
        </div>
      </div>

      {loading ? (
        <div className="flex-1 flex items-center justify-center">
          <Loader2 className="w-6 h-6 text-primary-600 animate-spin" />
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
          {!readOnly && (
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
              className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-primary-700 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
              title={detail?.visto ? 'Marcar como no leído' : 'Marcar como leído'}
            >
              {detail?.visto ? <Mail className="w-4 h-4" /> : <MailOpen className="w-4 h-4" />}
            </button>
            {inTrash || inSpam ? (
              <>
                <button
                  onClick={() => handleAction(inTrash ? 'restore' : 'unspam')}
                  disabled={actionLoading === 'restore' || actionLoading === 'unspam'}
                  className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-primary-700 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
                  title={inTrash ? 'Restaurar' : 'Quitar de spam'}
                >
                  <RotateCcw className="w-4 h-4" />
                </button>
                <button
                  onClick={() => handleAction('delete')}
                  disabled={actionLoading === 'delete'}
                  className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-red-500 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
                  title="Eliminar definitivamente"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </>
            ) : (
              <button
                onClick={() => handleAction('trash')}
                disabled={actionLoading === 'trash'}
                className="p-1.5 text-gray-400 dark:text-gray-500 hover:text-red-500 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-full transition-colors disabled:opacity-50"
                title="Eliminar"
              >
                <Trash2 className="w-4 h-4" />
              </button>
            )}
          </div>
          )}

          {/* Sender card — Gmail style */}
          <div className="flex items-start gap-3 mb-6">
            <div className="w-10 h-10 rounded-full bg-primary-700 flex items-center justify-center text-sm font-medium text-white flex-shrink-0 mt-0.5">
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
              <EmailBodyIframe html={detail.bodyHtml} />
            ) : (
              <p className="text-gray-500 dark:text-gray-400 italic whitespace-pre-wrap">
                {detail?.bodyPreview || 'Sin contenido visible'}
              </p>
            )}
          </div>

          {attachments.length > 0 && (
            <div className="mt-6 pt-4 border-t border-gray-200 dark:border-gray-700">
              <p className="text-xs font-medium text-gray-500 dark:text-gray-400 mb-2">
                Adjuntos ({attachments.length})
              </p>
              <div className="flex flex-wrap gap-2">
                {attachments.map((a, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => handleDownload(i, a.filename)}
                    disabled={downloading === i}
                    className="flex items-center gap-2 px-3 py-2 rounded-lg border border-gray-200 dark:border-gray-700 hover:bg-gray-50 dark:hover:bg-gray-700 text-sm disabled:opacity-60"
                  >
                    {downloading === i
                      ? <Loader2 className="w-4 h-4 animate-spin text-gray-400" />
                      : <Paperclip className="w-4 h-4 text-gray-400" />}
                    <span className="truncate max-w-[200px]">{a.filename}</span>
                    <span className="text-xs text-gray-400">{formatSize(a.size)}</span>
                  </button>
                ))}
              </div>
              {downloadError && (
                <p className="text-xs text-red-600 dark:text-red-400 mt-1.5">{downloadError}</p>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
