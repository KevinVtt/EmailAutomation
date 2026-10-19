import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Star, Trash2, X } from 'lucide-react';
import ChatPanel from '../ChatPanel';
import AssistantButton from './AssistantButton';
import ContactsTab from './ContactsTab';
import { useScreenScope } from '../../context/ScreenScopeContext';
import { api } from '../../lib/api';

export default function AssistantPanel() {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState('chat');
  const [results, setResults] = useState([]);
  const [error, setError] = useState('');
  const { scope } = useScreenScope();
  const navigate = useNavigate();
  // Solo tiene sentido cuando la lista actual restringe (Spam, Destacados).
  const restricted = Object.keys(scope.criteria || {}).length > 0;
  const [searchAll, setSearchAll] = useState(false);
  const [resetKey, setResetKey] = useState(0);
  // Se mostró al menos una búsqueda (aunque vuelva vacía): así el botón de
  // limpiar sigue disponible cuando el filtro no encontró nada.
  const [searched, setSearched] = useState(false);

  // Cambiar de lista devuelve el alcance al de esa lista.
  useEffect(() => setSearchAll(false), [scope.folder]);

  const applyResults = (emails) => {
    setResults(emails);
    setSearched(true);
  };

  const clearFilter = () => {
    setResults([]);
    setSearched(false);
    setResetKey((k) => k + 1);
  };

  const openEmail = (email) => {
    if (!email.id) return;
    // El detalle ocupa la pantalla: el panel se aparta.
    setOpen(false);
    navigate(`/email/${email.id}`, { state: { email } });
  };

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

        {restricted && (
          <div className="pt-1 pb-2 flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
            <span className="min-w-0 truncate">
              {searchAll ? 'Buscando en toda la cuenta' : `Buscando solo en ${scope.description}`}
            </span>
            <button
              type="button"
              onClick={() => setSearchAll((v) => !v)}
              className="ml-auto flex-shrink-0 font-medium text-primary-700 dark:text-primary-300 hover:underline"
            >
              {searchAll ? `Volver a ${scope.description}` : 'Buscar en todo'}
            </button>
          </div>
        )}

        {tab === 'contacts' ? (
          <div className="flex flex-col flex-1 min-h-0 pt-2">
            <ContactsTab />
          </div>
        ) : (
          <div className="flex flex-col gap-2 pt-2 flex-1 min-h-0">
            <div className="flex-1 min-h-0">
              <ChatPanel scope={searchAll ? null : scope} onResults={applyResults} resetKey={resetKey} />
            </div>

            {searched && (
              <div className="max-h-44 overflow-y-auto border-t border-primary-200 dark:border-gray-700 pt-2 space-y-1">
                <div className="flex items-center justify-between gap-2">
                  <p className="text-xs text-gray-500 dark:text-gray-400">
                    {results.length === 0
                      ? 'Sin resultados'
                      : `${results.length} resultado${results.length === 1 ? '' : 's'}`}
                  </p>
                  <button
                    type="button"
                    onClick={clearFilter}
                    className="text-xs font-medium text-primary-700 dark:text-primary-300 hover:underline"
                  >
                    Limpiar filtro
                  </button>
                </div>
                {results.map((email) => (
                  <div
                    key={email.providerEmailId}
                    className="flex items-center gap-2 text-sm px-1 py-1 rounded hover:bg-gray-50 dark:hover:bg-gray-700"
                  >
                    <button
                      type="button"
                      onClick={() => openEmail(email)}
                      title="Abrir email"
                      className="min-w-0 flex-1 text-left"
                    >
                      <p className="truncate text-gray-800 dark:text-gray-200">
                        {email.subject || '(sin asunto)'}
                      </p>
                      <p className="truncate text-xs text-gray-500 dark:text-gray-400">
                        {email.fromAddress}
                      </p>
                    </button>
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
