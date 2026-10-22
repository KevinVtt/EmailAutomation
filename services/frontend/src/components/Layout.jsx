import { useEffect } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { useTheme } from '../context/ThemeContext';
import { api } from '../lib/api';
import {
  Sparkles, LogOut, Mail, Sun, Moon,
  LayoutDashboard, Inbox, Star, ShieldAlert, Settings,
} from 'lucide-react';
import clsx from 'clsx';
import AssistantPanel from './assistant/AssistantPanel';
import { ScreenScopeProvider } from '../context/ScreenScopeContext';

// Cada cuánto se busca correo nuevo en segundo plano mientras la app está abierta.
const SYNC_INTERVAL_MS = 120000;
// Intervalo y foco pueden coincidir: sin esto salían dos POST /api/emails/sync.
let syncInFlight = false;

const NAV_ITEMS = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/inbox', label: 'Bandeja de entrada', icon: Inbox },
  { to: '/starred', label: 'Destacados', icon: Star },
  { to: '/spam', label: 'Spam', icon: ShieldAlert },
  { to: '/settings', label: 'Configuración', icon: Settings },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const { theme, toggleTheme } = useTheme();

  // El Dashboard sincroniza una vez por sesión; acá corre un sync de fondo en
  // todas las páginas para que el correo nuevo no dependa de apretar nada.
  useEffect(() => {
    if (!user?.provider) return;

    const syncNow = async () => {
      if (syncInFlight) return;
      syncInFlight = true;
      try {
        const { syncing } = await api.emails.syncStatus();
        if (!syncing) await api.emails.sync(user.provider);
      } catch {
        // Poll de fondo: si falla, el próximo intento lo resuelve.
      } finally {
        syncInFlight = false;
      }
    };

    const interval = setInterval(() => {
      if (document.visibilityState === 'visible') syncNow();
    }, SYNC_INTERVAL_MS);
    window.addEventListener('focus', syncNow);

    return () => {
      clearInterval(interval);
      window.removeEventListener('focus', syncNow);
    };
  }, [user?.provider]);

  return (
    <ScreenScopeProvider>
      <div className="min-h-screen flex flex-col bg-primary-50 dark:bg-gray-900 transition-colors">
      <header className="bg-white dark:bg-gray-800 border-b border-primary-200 dark:border-gray-700 sticky top-0 z-50 transition-colors">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center gap-2">
              <Sparkles className="w-6 h-6 text-accent-600" />
              <span className="font-bold text-lg text-gray-900 dark:text-gray-100">EmailFilter AI</span>
            </div>

            <div className="flex items-center gap-4">
              <div className="flex items-center gap-2">
                <div className="w-8 h-8 relative">
                  <div className="w-8 h-8 rounded-full bg-primary-100 dark:bg-primary-900/40 flex items-center justify-center">
                    <Mail className="w-4 h-4 text-primary-600 dark:text-primary-400" />
                  </div>
                  {user?.avatarUrl && (
                    <img
                      src={user.avatarUrl}
                      alt=""
                      referrerPolicy="no-referrer"
                      className="w-8 h-8 rounded-full absolute inset-0"
                      onError={(e) => { e.currentTarget.style.display = 'none'; }}
                    />
                  )}
                </div>
                <span className="text-sm text-gray-700 dark:text-gray-300 hidden sm:block">
                  {user?.name || user?.email}
                </span>
              </div>
              <button
                onClick={toggleTheme}
                className="p-2 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                title={theme === 'dark' ? 'Modo claro' : 'Modo oscuro'}
              >
                {theme === 'dark' ? <Sun className="w-5 h-5" /> : <Moon className="w-5 h-5" />}
              </button>
              <button
                onClick={logout}
                className="p-2 text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
                title="Cerrar sesión"
              >
                <LogOut className="w-5 h-5" />
              </button>
            </div>
          </div>
        </div>
      </header>

      <div className="flex flex-1">
        <aside className="w-52 sm:w-56 flex-shrink-0 bg-white dark:bg-gray-800 border-r border-primary-200 dark:border-gray-700 transition-colors">
          <nav className="p-3 space-y-1 sticky top-16">
            {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) => clsx(
                  'flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300'
                    : 'text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 hover:text-gray-900 dark:hover:text-gray-100'
                )}
              >
                <Icon className="w-4 h-4 flex-shrink-0" />
                <span className="truncate">{label}</span>
              </NavLink>
            ))}
          </nav>
        </aside>

        <main className="flex-1 min-w-0">
          <Outlet />
        </main>
      </div>
      </div>

      <AssistantPanel />
    </ScreenScopeProvider>
  );
}