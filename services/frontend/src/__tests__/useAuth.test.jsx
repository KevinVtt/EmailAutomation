import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useAuth } from '../hooks/useAuth';
import { AuthContext } from '../context/AuthContext';

describe('useAuth hook', () => {
  it('returns context when used within AuthProvider', () => {
    const wrapper = ({ children }) => (
      <AuthContext.Provider value={{ user: { id: '123' }, loading: false, login: async () => {}, logout: () => {} }}>
        {children}
      </AuthContext.Provider>
    );

    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.user.id).toBe('123');
    expect(result.current.loading).toBe(false);
  });

  it('throws error when used outside AuthProvider', () => {
    try {
      renderHook(() => useAuth());
    } catch (e) {
      expect(e.message).toBe('useAuth must be used within AuthProvider');
    }
  });
});
