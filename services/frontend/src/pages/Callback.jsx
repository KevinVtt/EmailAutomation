import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { Sparkles } from 'lucide-react';

export default function Callback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { login } = useAuth();
  const [error, setError] = useState(null);

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    const provider = searchParams.get('provider') || 'google';

    if (!code) {
      setError('No authorization code received');
      return;
    }

    login(provider, code)
      .then(() => navigate('/', { replace: true }))
      .catch((err) => {
        console.error('Auth callback failed', err);
        setError('Authentication failed. Please try again.');
      });
  }, []);

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-primary-50 to-blue-100 dark:from-gray-900 dark:to-gray-800 transition-colors">
        <div className="card dark:bg-gray-800 dark:border-gray-700 text-center max-w-sm">
          <p className="text-red-600 dark:text-red-400 mb-4">{error}</p>
          <button onClick={() => navigate('/login')} className="btn-primary">
            Volver a intentar
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-primary-50 to-blue-100 dark:from-gray-900 dark:to-gray-800 transition-colors">
      <div className="text-center">
        <Sparkles className="w-12 h-12 text-primary-600 animate-pulse mx-auto mb-4" />
        <p className="text-gray-600 dark:text-gray-400">Autenticando...</p>
      </div>
    </div>
  );
}
