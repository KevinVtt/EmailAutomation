import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import Login from '../pages/Login';
import Callback from '../pages/Callback';

const mockAuthContext = (loginMock) => ({
  user: null,
  loading: false,
  login: loginMock,
  logout: () => {},
});

function renderLogin(loginMock = vi.fn()) {
  return render(
    <AuthContext.Provider value={mockAuthContext(loginMock)}>
      <MemoryRouter>
        <Login />
      </MemoryRouter>
    </AuthContext.Provider>
  );
}

function renderCallback(loginMock, initialEntry = '/auth/callback?code=test-code') {
  return render(
    <AuthContext.Provider value={mockAuthContext(loginMock)}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Callback />
      </MemoryRouter>
    </AuthContext.Provider>
  );
}

describe('OAuth flow provider persistence', () => {
  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({ authUrl: 'https://login.microsoftonline.com/...' })),
    });
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
    });
  });

  describe('Login', () => {
    it('saves outlook to sessionStorage before redirecting to OAuth', async () => {
      renderLogin();

      const outlookBtn = screen.getByText(/Continuar con Outlook/i).closest('button');
      fireEvent.click(outlookBtn);

      await waitFor(() => {
        expect(window.location.href).toBe('https://login.microsoftonline.com/...');
      });
      expect(sessionStorage.getItem('oauthProvider')).toBe('outlook');
    });

    it('saves google to sessionStorage before redirecting to OAuth', async () => {
      renderLogin();

      const googleBtn = screen.getByText(/Continuar con Google/i).closest('button');
      fireEvent.click(googleBtn);

      await waitFor(() => {
        expect(window.location.href).toBe('https://login.microsoftonline.com/...');
      });
      expect(sessionStorage.getItem('oauthProvider')).toBe('google');
    });
  });

  describe('Callback', () => {
    it('uses provider from sessionStorage and clears it after login', async () => {
      sessionStorage.setItem('oauthProvider', 'outlook');
      const loginMock = vi.fn().mockResolvedValue({ userId: '1' });

      renderCallback(loginMock);

      await waitFor(() => {
        expect(loginMock).toHaveBeenCalledWith('outlook', 'test-code');
      });
      expect(sessionStorage.getItem('oauthProvider')).toBeNull();
    });

    it('falls back to provider from URL param when sessionStorage is empty', async () => {
      const loginMock = vi.fn().mockResolvedValue({ userId: '1' });

      renderCallback(loginMock, '/auth/callback?code=test-code&provider=outlook');

      await waitFor(() => {
        expect(loginMock).toHaveBeenCalledWith('outlook', 'test-code');
      });
    });

    it('falls back to google with warning when no provider in sessionStorage or URL', async () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const loginMock = vi.fn().mockResolvedValue({ userId: '1' });

      renderCallback(loginMock);

      await waitFor(() => {
        expect(loginMock).toHaveBeenCalledWith('google', 'test-code');
      });
      expect(warnSpy).toHaveBeenCalled();
      warnSpy.mockRestore();
    });
  });
});