import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { AuthContext, AuthProvider } from '../context/AuthContext';

const validJwt = `${btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${btoa(JSON.stringify({ sub: 'test-user', exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;

describe('AuthContext', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('provides initial state with no user and loading true then false', async () => {
    let contextValue;
    function TestComponent() {
      return (
        <AuthContext.Consumer>
          {(value) => {
            contextValue = value;
            return <div data-testid="loading">{String(value.loading)}</div>;
          }}
        </AuthContext.Consumer>
      );
    }

    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    );

    expect(screen.getByTestId('loading')).toHaveTextContent('false');
    expect(contextValue.user).toBeNull();
  });

  it('loads user from localStorage when available', () => {
    localStorage.setItem('accessToken', validJwt);
    localStorage.setItem('refreshToken', 'refresh-456');
    localStorage.setItem('user', JSON.stringify({ id: '123', email: 'test@example.com', name: 'Test' }));

    let contextValue;
    function TestComponent() {
      return (
        <AuthContext.Consumer>
          {(value) => {
            contextValue = value;
            return <div data-testid="user-email">{value.user?.email}</div>;
          }}
        </AuthContext.Consumer>
      );
    }

    render(
      <AuthProvider>
        <TestComponent />
      </AuthProvider>
    );

    expect(screen.getByTestId('user-email')).toHaveTextContent('test@example.com');
    expect(contextValue.user.email).toBe('test@example.com');
  });
});
