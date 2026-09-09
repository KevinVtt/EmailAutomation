import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import Login from '../pages/Login';

const mockAuthContext = {
  user: null,
  loading: false,
  login: async () => {},
  logout: () => {},
};

function renderLogin() {
  return render(
    <AuthContext.Provider value={mockAuthContext}>
      <MemoryRouter>
        <Login />
      </MemoryRouter>
    </AuthContext.Provider>
  );
}

describe('Login OAuth error handling', () => {
  beforeEach(() => {
    sessionStorage.clear();
    global.fetch = vi.fn().mockRejectedValue(new Error('Network error'));
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
    });
  });

  it('shows a clear error message when the OAuth URL fetch fails', async () => {
    renderLogin();

    const googleBtn = screen.getByText(/Continuar con Google/i).closest('button');
    fireEvent.click(googleBtn);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText(/No se pudo iniciar sesión con Google/i)).toBeTruthy();
  });

  it('clears the error and redirects when a retry succeeds', async () => {
    renderLogin();

    const googleBtn = screen.getByText(/Continuar con Google/i).closest('button');
    fireEvent.click(googleBtn);
    expect(await screen.findByRole('alert')).toBeTruthy();

    // Second attempt succeeds → error cleared, redirect happens
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({ authUrl: 'https://accounts.google.com/...' })),
    });
    fireEvent.click(googleBtn);

    await waitFor(() => {
      expect(window.location.href).toBe('https://accounts.google.com/...');
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });
});