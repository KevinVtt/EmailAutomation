import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import Login from '../pages/Login';

const mockAuthContext = {
  user: null,
  loading: false,
  login: async () => {},
  logout: () => {},
};

function renderWithProviders(ui) {
  return render(
    <AuthContext.Provider value={mockAuthContext}>
      <BrowserRouter>{ui}</BrowserRouter>
    </AuthContext.Provider>
  );
}

describe('Login page', () => {
  beforeEach(() => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({
        authUrl: 'https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&state=csrf-state-1',
      })),
    });
    window.open = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
    });
  });

  it('offers Google only while Outlook is parked', () => {
    renderWithProviders(<Login />);
    expect(screen.getByText(/Continuar con Google/i)).toBeTruthy();
    expect(screen.queryByText(/Continuar con Outlook/i)).toBeNull();
  });

  it('redirects to Google OAuth URL on click and stores the CSRF state', async () => {
    renderWithProviders(<Login />);

    const googleBtn = screen.getByText(/Continuar con Google/i).closest('button');
    fireEvent.click(googleBtn);

    await waitFor(() => {
      expect(window.location.href).toBe(
        'https://accounts.google.com/o/oauth2/v2/auth?client_id=abc&state=csrf-state-1'
      );
    });
    expect(sessionStorage.getItem('oauthState')).toBe('csrf-state-1');
    sessionStorage.clear();
  });
});
