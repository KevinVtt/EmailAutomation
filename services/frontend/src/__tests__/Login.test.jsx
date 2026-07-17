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
      text: () => Promise.resolve(JSON.stringify({
        authUrl: 'https://accounts.google.com/o/oauth2/v2/auth?...',
      })),
    });
    window.open = vi.fn();
    Object.defineProperty(window, 'location', {
      value: { href: '' },
      writable: true,
    });
  });

  it('renders login buttons', () => {
    renderWithProviders(<Login />);
    expect(screen.getByText(/Continuar con Google/i)).toBeTruthy();
    expect(screen.getByText(/Continuar con Outlook/i)).toBeTruthy();
  });

  it('redirects to Google OAuth URL on click', async () => {
    renderWithProviders(<Login />);

    const googleBtn = screen.getByText(/Continuar con Google/i).closest('button');
    fireEvent.click(googleBtn);

    await waitFor(() => {
      expect(window.location.href).toBe('https://accounts.google.com/o/oauth2/v2/auth?...');
    });
  });
});
