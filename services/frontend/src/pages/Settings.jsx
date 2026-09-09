import { useAuth } from '../hooks/useAuth';
import { Mail, LogOut } from 'lucide-react';

export default function Settings() {
  const { user, logout } = useAuth();

  return (
    <div className="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
      <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100 mb-6">Configuración</h1>

      <div className="card dark:bg-gray-800 dark:border-gray-700">
        <div className="flex items-center gap-4 pb-4 mb-4 border-b border-gray-200 dark:border-gray-700">
          <div className="w-16 h-16 relative">
            <div className="w-16 h-16 rounded-full bg-primary-100 dark:bg-primary-900/40 flex items-center justify-center">
              <Mail className="w-8 h-8 text-primary-600 dark:text-primary-400" />
            </div>
            {user?.avatarUrl && (
              <img
                src={user.avatarUrl}
                alt=""
                referrerPolicy="no-referrer"
                className="w-16 h-16 rounded-full absolute inset-0"
                onError={(e) => { e.currentTarget.style.display = 'none'; }}
              />
            )}
          </div>
          <div className="min-w-0">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100 truncate">
              {user?.name || 'Usuario'}
            </h2>
            <p className="text-sm text-gray-500 dark:text-gray-400 truncate">{user?.email}</p>
          </div>
        </div>

        <dl className="space-y-3 text-sm">
          <div className="flex items-center justify-between gap-4">
            <dt className="text-gray-500 dark:text-gray-400">Proveedor</dt>
            <dd className="text-gray-900 dark:text-gray-100 capitalize">{user?.provider || '—'}</dd>
          </div>
          <div className="flex items-center justify-between gap-4">
            <dt className="text-gray-500 dark:text-gray-400">ID de usuario</dt>
            <dd className="text-gray-900 dark:text-gray-100 font-mono text-xs truncate">{user?.id || '—'}</dd>
          </div>
        </dl>

        <div className="mt-6 pt-4 border-t border-gray-200 dark:border-gray-700">
          <button
            onClick={logout}
            className="flex items-center gap-2 px-4 py-2 rounded-lg font-medium bg-red-600 text-white hover:bg-red-700 transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-2"
          >
            <LogOut className="w-4 h-4" />
            Cerrar sesión
          </button>
        </div>
      </div>
    </div>
  );
}