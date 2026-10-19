import { createContext, useCallback, useContext, useState } from 'react';

const DEFAULT_SCOPE = { folder: 'inbox', criteria: {}, description: 'Bandeja de entrada' };

export const EMPTY_SCOPE = { folder: '', criteria: {}, description: '' };

const ScreenScopeContext = createContext(null);

export function ScreenScopeProvider({ children }) {
  const [scope, setScopeState] = useState(DEFAULT_SCOPE);
  const setScope = useCallback((next) => setScopeState(next), []);

  return (
    <ScreenScopeContext.Provider value={{ scope, setScope }}>
      {children}
    </ScreenScopeContext.Provider>
  );
}

// Sin provider (tests, montajes sueltos) el asistente queda sin alcance, no rompe.
export function useScreenScope() {
  return useContext(ScreenScopeContext) || { scope: DEFAULT_SCOPE, setScope: () => {} };
}
