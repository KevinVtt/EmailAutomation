import { useAuth } from '../hooks/useAuth';
import { Navigate } from 'react-router-dom';
import { api } from '../lib/api';
import { useState } from 'react';
import { Mail, Sparkles } from 'lucide-react';

const PROVIDERS = [
  {
    id: 'google',
    name: 'Google',
    icon: Mail,
    color: 'bg-red-500 hover:bg-red-600',
  },
  {
    id: 'outlook',
    name: 'Outlook',
    icon: Mail,
    color: 'bg-blue-500 hover:bg-blue-600',
  },
];

export default function Login() {
  const { user, loading } = useAuth();
  const [authing, setAuthing] = useState(null);

  if (loading) return null;
  if (user) return <Navigate to="/" replace />;

  const handleLogin = async (provider) => {
    setAuthing(provider);
    try {
      const { authUrl } = await api.auth.getOAuthUrl(provider);
      window.location.href = authUrl;
    } catch (err) {
      console.error('Failed to get auth URL', err);
      setAuthing(null);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-primary-50 to-blue-100 p-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 bg-primary-600 rounded-2xl mb-4">
            <Sparkles className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-3xl font-bold text-gray-900">Email Filter AI</h1>
          <p className="text-gray-600 mt-2">
            Filtra tus emails con inteligencia artificial
          </p>
        </div>

        <div className="card space-y-4">
          <p className="text-sm text-gray-600 text-center mb-4">
            Conecta tu cuenta de email para empezar
          </p>

          {PROVIDERS.map(({ id, name, icon: Icon, color }) => (
            <button
              key={id}
              onClick={() => handleLogin(id)}
              disabled={authing === id}
              className={`w-full flex items-center justify-center gap-3 text-white px-6 py-3 rounded-lg font-medium transition-all duration-200 disabled:opacity-50 ${color}`}
            >
              {authing === id ? (
                <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-white" />
              ) : (
                <Icon className="w-5 h-5" />
              )}
              Continuar con {name}
            </button>
          ))}
        </div>

        <p className="text-xs text-gray-500 text-center mt-6">
          Al iniciar sesión, autorizas a la app a leer y gestionar tus emails.
        </p>
      </div>
    </div>
  );
}
