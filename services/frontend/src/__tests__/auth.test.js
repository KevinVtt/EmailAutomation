import { describe, it, expect, beforeEach } from 'vitest';
import { getStoredAuth, storeAuth, clearAuth } from '../lib/auth';

const validJwt = `${btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${btoa(JSON.stringify({ sub: 'test-user', exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`;

describe('auth lib', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('storeAuth saves auth data to localStorage', () => {
    storeAuth({
      userId: '123',
      email: 'test@example.com',
      name: 'Test',
      avatarUrl: 'http://avatar.com/img.png',
      accessToken: 'token-123',
      refreshToken: 'refresh-456',
    });

    expect(localStorage.getItem('accessToken')).toBe('token-123');
    expect(localStorage.getItem('refreshToken')).toBe('refresh-456');
    expect(localStorage.getItem('user')).toBeTruthy();
  });

  it('getStoredAuth returns null when no auth stored', () => {
    const result = getStoredAuth();
    expect(result).toBeNull();
  });

  it('getStoredAuth returns auth data when stored', () => {
    storeAuth({
      userId: '123',
      email: 'test@example.com',
      name: 'Test',
      avatarUrl: null,
      accessToken: validJwt,
      refreshToken: 'refresh-456',
    });

    const result = getStoredAuth();
    expect(result.accessToken).toBe(validJwt);
    expect(result.user.email).toBe('test@example.com');
  });

  it('clearAuth removes all auth data', () => {
    storeAuth({
      userId: '123',
      email: 'test@example.com',
      name: 'Test',
      avatarUrl: null,
      accessToken: 'token-123',
      refreshToken: 'refresh-456',
    });

    clearAuth();

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
  });
});
