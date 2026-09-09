import { describe, it, expect, vi, beforeAll } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import { ThemeProvider } from '../context/ThemeContext';
import Layout from '../components/Layout';
import App from '../App';

// jsdom does not implement matchMedia — ThemeProvider reads prefers-color-scheme on mount.
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
});

const mockAuth = {
  user: { id: 'user-1', email: 'test@example.com', name: 'Test User', provider: 'google' },
  loading: false,
  login: vi.fn(),
  logout: vi.fn(),
};

function renderLayout(initialEntry = '/') {
  return render(
    <AuthContext.Provider value={mockAuth}>
      <ThemeProvider>
        <MemoryRouter initialEntries={[initialEntry]}>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<div>Dashboard view</div>} />
              <Route path="inbox" element={<div>Inbox view</div>} />
              <Route path="starred" element={<div>Starred view</div>} />
              <Route path="spam" element={<div>Spam view</div>} />
              <Route path="settings" element={<div>Settings view</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    </AuthContext.Provider>
  );
}

describe('Layout sidebar navigation', () => {
  it('renders all five nav items', () => {
    renderLayout();
    expect(screen.getByText('Dashboard')).toBeTruthy();
    expect(screen.getByText('Bandeja de entrada')).toBeTruthy();
    expect(screen.getByText('Destacados')).toBeTruthy();
    expect(screen.getByText('Spam')).toBeTruthy();
    expect(screen.getByText('Configuración')).toBeTruthy();
  });

  it('marks the active route with the primary style', () => {
    renderLayout('/starred');
    const starredLink = screen.getByText('Destacados').closest('a');
    expect(starredLink.className).toContain('bg-primary-50');
  });

  it('navigates to settings when clicking the nav item', () => {
    renderLayout();
    fireEvent.click(screen.getByText('Configuración'));
    expect(screen.getByText('Settings view')).toBeTruthy();
  });

  it('navigates to starred when clicking the nav item', () => {
    renderLayout();
    fireEvent.click(screen.getByText('Destacados'));
    expect(screen.getByText('Starred view')).toBeTruthy();
  });
});

describe('App lazy routes', () => {
  it('renders the Settings page through the lazy route', async () => {
    render(
      <AuthContext.Provider value={mockAuth}>
        <ThemeProvider>
          <MemoryRouter initialEntries={['/settings']}>
            <App />
          </MemoryRouter>
        </ThemeProvider>
      </AuthContext.Provider>
    );
    expect(await screen.findByText('Cerrar sesión')).toBeTruthy();
    expect(await screen.findByText('test@example.com')).toBeTruthy();
  });
});