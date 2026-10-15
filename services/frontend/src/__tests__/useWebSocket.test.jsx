import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';

const {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  onWebSocketConnect,
  onWebSocketError,
} = vi.hoisted(() => ({
  connectWebSocket: vi.fn(),
  disconnectWebSocket: vi.fn(),
  subscribeToTopic: vi.fn(() => vi.fn()),
  onWebSocketConnect: vi.fn(),
  onWebSocketError: vi.fn(),
}));

vi.mock('../lib/websocket', () => ({
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  onWebSocketConnect,
  onWebSocketError,
}));

const makeToken = (sub) => `header.${btoa(JSON.stringify({ sub }))}.sig`;

describe('useWebSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('connects and subscribes to the user queues (no userId in the destination)', () => {
    const handlers = { onEmails: vi.fn(), onChat: vi.fn(), onNotifications: vi.fn() };
    const { unmount } = renderHook(() => useWebSocket(makeToken('user-123'), handlers));

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-123'));

    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/emails', expect.any(Function));
    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/chat', expect.any(Function));
    expect(subscribeToTopic).toHaveBeenCalledWith('/user/queue/notifications', expect.any(Function));
    for (const [topic] of subscribeToTopic.mock.calls) {
      expect(topic).not.toContain('user-123');
    }

    const offs = subscribeToTopic.mock.results.map((r) => r.value);
    unmount();
    offs.forEach((off) => expect(off).toHaveBeenCalled());
  });

  it('routes messages to the handler for the topic', () => {
    const onEmails = vi.fn();
    renderHook(() => useWebSocket(makeToken('user-1'), { onEmails }));

    const [, callback] = subscribeToTopic.mock.calls.find(([topic]) => topic === '/user/queue/emails');
    act(() => callback({ id: 'e1' }));

    expect(onEmails).toHaveBeenCalledWith({ id: 'e1' });
  });

  it('forwards the connect and error callbacks', () => {
    const onConnect = vi.fn();
    const onError = vi.fn();
    renderHook(() => useWebSocket(makeToken('user-1'), { onConnect, onError }));

    expect(onWebSocketConnect).toHaveBeenCalled();
    expect(onWebSocketError).toHaveBeenCalled();

    act(() => onWebSocketConnect.mock.calls[0][0]());
    expect(onConnect).toHaveBeenCalled();

    act(() => onWebSocketError.mock.calls[0][0]('boom'));
    expect(onError).toHaveBeenCalledWith('boom');
  });

  it('leaves the shared connection alive on unmount (the logout closes it)', () => {
    const { unmount } = renderHook(() => useWebSocket(makeToken('user-1'), { onEmails: vi.fn() }));

    unmount();

    expect(disconnectWebSocket).not.toHaveBeenCalled();
  });

  it('connects even when the JWT payload has no sub claim (no userId extraction)', () => {
    const token = `header.${btoa(JSON.stringify({ role: 'user' }))}.sig`;
    renderHook(() => useWebSocket(token, {}));

    expect(connectWebSocket).toHaveBeenCalledWith(token);
  });

  it('connects with the new token when the token changes', () => {
    const { rerender } = renderHook(({ token }) => useWebSocket(token, {}), {
      initialProps: { token: makeToken('user-1') },
    });

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-1'));

    rerender({ token: makeToken('user-2') });

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-2'));
  });

  it('does not connect when there is no token', () => {
    renderHook(() => useWebSocket(null, {}));

    expect(connectWebSocket).not.toHaveBeenCalled();
  });
});
