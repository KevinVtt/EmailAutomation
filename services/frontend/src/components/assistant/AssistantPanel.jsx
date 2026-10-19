import { useState } from 'react';
import { Star, Trash2, X } from 'lucide-react';
import ChatPanel from '../ChatPanel';
import AssistantButton from './AssistantButton';
import { useScreenScope } from '../../context/ScreenScopeContext';
import { api } from '../../lib/api';

export default function AssistantPanel() {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState('chat');
  const [results, setResults] = useState([]);
  const [error, setError] = useState('');
  const { scope } = useScreenScope();

  const act = async (email, action) => {
    if (action === 'trash' && !window.confirm(`¿Mover "${email.subject || 'este mail'}" a la papelera?`)) {
      return;
    }
    setError('');
    try {
      await api.emails.bulkAction(email.provider || 'google', [email.providerEmailId], action);
      setResults((prev) => prev.filter((e) => e.providerEmailId !== email.providerEmailId));
    } catch {
      setError('No se pudo aplicar la acción.');
    }
  };

  return (
    <>
      <AssistantButton open={open} onToggle={() => setOpen((v) => !v)} />

      {/* Queda montado al cerrarse: la conversación sobrevive a abrir y cerrar. */}
      <div
        data-testid="assistant-panel"
        data-open={open}
        className={`${open ? 'flex' : 'hidden'} fixed bottom-24 right-6 z-40 w-[380px] max-w-[calc(100vw-2rem)] h-[560px] max-h-[calc(100vh-8rem)] flex-col card bg-white dark:bg-gray-800 dark:border-gray-700 shadow-2xl`}
      >
        <div className="flex items-center justify-between pb-2 border-b border-primary-200 dark:border-gray-700">
          <div className="flex gap-1" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'chat'}
              onClick={() => setTab('chat')}
              className={`px-3 py-1 rounded-lg text-sm font-medium ${
                tab === 'chat'
                  ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                  : 'text-gray-600 dark:text-gray-300'
              }`}
            >
              Chat
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'contacts'}
              onClick={() => setTab('contacts')}
              className={`px-3 py-1 rounded-lg text-sm font-medium ${
                tab === 'contacts'
                  ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                  : 'text-gray-600 dark:text-gray-300'
              }`}
            >
              Contactos
            </button>
          </div>
          <button
            type="button"
            aria-label="Cerrar asistente"
            onClick={() => setOpen(false)}
            className="p-1 text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-100"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {scope.description && (
          <p className="pt-1 pb-2 text-xs text-gray-500 dark:text-gray-400">
            Filtrando en: {scope.description}
          </p>
        )}

        {tab === 'contacts' ? (
          <div className="p-4 text-sm text-gray-600 dark:text-gray-300">
            Elegí un contacto para escribirle.
          </div>
        ) : (
          <div className="flex flex-col gap-2 pt-2 flex-1 min-h-0">
            <div className="flex-1 min-h-0">
              <ChatPanel scope={scope} onResults={setResults} />
            </div>

            {results.length > 0 && (
              <div className="max-h-44 overflow-y-auto border-t border-primary-200 dark:border-gray-700 pt-2 space-y-1">
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  {results.length} resultado{results.length === 1 ? '' : 's'}
                </p>
                {results.map((email) => (
                  <div
                    key={email.providerEmailId}
                    className="flex items-center gap-2 text-sm px-1 py-1 rounded hover:bg-gray-50 dark:hover:bg-gray-700"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-gray-800 dark:text-gray-200">
                        {email.subject || '(sin asunto)'}
                      </p>
                      <p className="truncate text-xs text-gray-500 dark:text-gray-400">
                        {email.fromAddress}
                      </p>
                    </div>
                    <button
                      type="button"
                      aria-label={`Destacar ${email.subject || ''}`.trim()}
                      onClick={() => act(email, 'star')}
                      className="p-1 text-gray-500 hover:text-yellow-500 dark:text-gray-400"
                    >
                      <Star className="w-4 h-4" />
                    </button>
                    <button
                      type="button"
                      aria-label={`Eliminar ${email.subject || ''}`.trim()}
                      onClick={() => act(email, 'trash')}
                      className="p-1 text-gray-500 hover:text-red-600 dark:text-gray-400"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}

            {error && <p className="text-xs text-red-600 dark:text-red-400">{error}</p>}
          </div>
        )}
      </div>
    </>
  );
}
