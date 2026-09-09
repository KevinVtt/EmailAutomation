import { createContext, useState, useEffect, useCallback } from 'react';
import { getStoredAuth, storeAuth, clearAuth } from '../lib/auth';
import { api } from '../lib/api';

export const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const auth = getStoredAuth();
    if (auth) {
      setUser(auth.user);
    }
    setLoading(false);
  }, []);

  const login = useCallback(async (provider, authorizationCode) => {
    const response = await api.auth.callback(provider, authorizationCode);
    storeAuth(response, provider);
    setUser({
      id: response.userId,
      email: response.email,
      name: response.name,
      avatarUrl: response.avatarUrl,
      provider,
    });
    return response;
  }, []);

  const logout = useCallback(() => {
    clearAuth();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
