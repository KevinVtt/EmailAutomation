import { useAuth } from '../hooks/useAuth';
import { Navigate } from 'react-router-dom';
import { api } from '../lib/api';
import { useState } from 'react';
import { Mail, Sparkles, AlertCircle, ShieldCheck, ChevronRight } from 'lucide-react';

const PROVIDERS = [
  {
    id: 'google',
    name: 'Google',
    description: 'Conecta tu cuenta de Gmail',
    icon: Mail,
    iconBg: 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400',
    hoverBorder: 'hover:border-red-300 dark:hover:border-red-700',
  },
  {
    id: 'outlook',
    name: 'Outlook',
    description: 'Conecta tu cuenta de Microsoft 365',
    icon: Mail,
    iconBg: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400',
    hoverBorder: 'hover:border-blue-300 dark:hover:border-blue-700',
  },
];

const PROVIDER_LABELS = { google: 'Google', outlook: 'Outlook' };

export default function Login() {
  const { user, loading } = useAuth();
  const [authing, setAuthing] = useState(null);
  const [error, setError] = useState(null);

  if (loading) return null;
  if (user) return <Navigate to="/" replace />;

  const handleLogin = async (provider) => {
    setAuthing(provider);
    setError(null);
    try {
      const { authUrl } = await api.auth.getOAuthUrl(provider);
      // Persist provider so Callback can complete the OAuth flow for the right provider
      sessionStorage.setItem('oauthProvider', provider);
      window.location.href = authUrl;
    } catch (err) {
      console.error('Failed to get auth URL', err);
      setError(
        `No se pudo iniciar sesión con ${PROVIDER_LABELS[provider] || provider}. Verifica tu conexión e inténtalo de nuevo.`
      );
      setAuthing(null);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-primary-50 to-blue-100 dark:from-gray-900 dark:to-gray-800 p-4 transition-colors">
      <div className="w-full max-w-md">
        {/* Branding */}
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-primary-600 rounded-2xl mb-4 shadow-lg shadow-primary-600/20">
            <Sparkles className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-3xl font-bold text-gray-900 dark:text-gray-100">Email Filter AI</h1>
          <p className="text-gray-600 dark:text-gray-400 mt-2">
            Filtra tus emails con inteligencia artificial
          </p>
        </div>

        <div className="card dark:bg-gray-800 dark:border-gray-700 space-y-3">
          <p className="text-sm text-gray-600 dark:text-gray-400 text-center mb-2">
            Conecta tu cuenta de email para empezar
          </p>

          {error && (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-lg bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 px-3 py-2 text-sm text-red-700 dark:text-red-300"
            >
              <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
              <span>{error}</span>
            </div>
          )}

          {PROVIDERS.map(({ id, name, description, icon: Icon, iconBg, hoverBorder }) => (
            <button
              key={id}
              onClick={() => handleLogin(id)}
              disabled={authing === id}
              className={`w-full flex items-center gap-3 text-left px-4 py-3 rounded-xl border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 transition-all duration-200 hover:shadow-md disabled:opacity-60 disabled:cursor-not-allowed ${hoverBorder}`}
            >
              <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${iconBg}`}>
                {authing === id ? (
                  <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-current" />
                ) : (
                  <Icon className="w-5 h-5" />
                )}
              </div>
              <div className="flex-1 min-w-0">
                <div className="text-sm font-medium text-gray-900 dark:text-gray-100">
                  Continuar con {name}
                </div>
                <div className="text-xs text-gray-500 dark:text-gray-400 truncate">{description}</div>
              </div>
              <ChevronRight className="w-4 h-4 text-gray-400 flex-shrink-0" />
            </button>
          ))}
        </div>

        <div className="flex items-center justify-center gap-1.5 text-xs text-gray-500 dark:text-gray-500 mt-6">
          <ShieldCheck className="w-3.5 h-3.5 flex-shrink-0" />
          <span>Al iniciar sesión, autorizas a la app a leer y gestionar tus emails.</span>
        </div>
      </div>
    </div>
  );
}