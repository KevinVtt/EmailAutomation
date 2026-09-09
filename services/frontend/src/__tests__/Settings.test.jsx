import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { AuthContext } from '../context/AuthContext';
import Settings from '../pages/Settings';

function renderSettings(user, logout = vi.fn()) {
  return render(
    <AuthContext.Provider value={{ user, loading: false, login: vi.fn(), logout }}>
      <Settings />
    </AuthContext.Provider>
  );
}

describe('Settings page', () => {
  it('shows user name, email and provider', () => {
    renderSettings({ id: 'u1', email: 'ana@test.com', name: 'Ana', provider: 'outlook' });
    expect(screen.getByText('Ana')).toBeTruthy();
    expect(screen.getByText('ana@test.com')).toBeTruthy();
    expect(screen.getByText('outlook')).toBeTruthy();
  });

  it('shows fallback values when user data is missing', () => {
    renderSettings({ id: 'u1', email: 'ana@test.com', provider: 'google' });
    expect(screen.getByText('Usuario')).toBeTruthy();
  });

  it('calls logout when clicking the logout button', () => {
    const logout = vi.fn();
    renderSettings({ id: 'u1', email: 'a@b.com', name: 'Ana', provider: 'google' }, logout);
    fireEvent.click(screen.getByText('Cerrar sesión'));
    expect(logout).toHaveBeenCalled();
  });
});