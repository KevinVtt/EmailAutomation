import { Outlet } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { Sparkles, LogOut, Mail } from 'lucide-react';

export default function Layout() {
  const { user, logout } = useAuth();

  return (
    <div className="min-h-screen flex flex-col bg-gray-50">
      <header className="bg-white border-b border-gray-200 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center gap-2">
              <Sparkles className="w-6 h-6 text-primary-600" />
              <span className="font-bold text-lg text-gray-900">EmailFilter AI</span>
            </div>

            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2">
                {user?.avatarUrl ? (
                  <img src={user.avatarUrl} alt="" className="w-8 h-8 rounded-full" />
                ) : (
                  <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                    <Mail className="w-4 h-4 text-primary-600" />
                  </div>
                )}
                <span className="text-sm text-gray-700 hidden sm:block">
                  {user?.name || user?.email}
                </span>
              </div>
              <button
                onClick={logout}
                className="p-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
                title="Cerrar sesión"
              >
                <LogOut className="w-5 h-5" />
              </button>
            </div>
          </div>
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}
