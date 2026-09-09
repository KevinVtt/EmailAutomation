import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useWebSocket } from '../hooks/useWebSocket';

const { connectWebSocket, disconnectWebSocket, subscribeToUser, unsubscribe } = vi.hoisted(() => ({
  connectWebSocket: vi.fn(),
  disconnectWebSocket: vi.fn(),
  subscribeToUser: vi.fn(),
  unsubscribe: vi.fn(),
}));

vi.mock('../lib/websocket', () => ({
  connectWebSocket,
  disconnectWebSocket,
  subscribeToUser,
  unsubscribe,
}));

const makeToken = (sub) => `header.${btoa(JSON.stringify({ sub }))}.sig`;

describe('useWebSocket', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('connects with the access token and subscribes to user queues on connect', () => {
    const handlers = { onEmails: vi.fn(), onChat: vi.fn(), onNotifications: vi.fn() };
    renderHook(() => useWebSocket(makeToken('user-123'), handlers));

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-123'), expect.any(Object));

    const { onConnect } = connectWebSocket.mock.calls[0][1];
    act(() => onConnect());

    expect(subscribeToUser).toHaveBeenCalledWith('user-123', 'queue/emails', expect.any(Function));
    expect(subscribeToUser).toHaveBeenCalledWith('user-123', 'queue/chat', expect.any(Function));
    expect(subscribeToUser).toHaveBeenCalledWith('user-123', 'queue/notifications', expect.any(Function));
  });

  it('reconnects with the new token when the token changes', () => {
    const { rerender } = renderHook(({ token }) => useWebSocket(token, {}), {
      initialProps: { token: makeToken('user-1') },
    });

    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-1'), expect.any(Object));

    rerender({ token: makeToken('user-2') });

    expect(disconnectWebSocket).toHaveBeenCalled();
    expect(connectWebSocket).toHaveBeenCalledWith(makeToken('user-2'), expect.any(Object));
  });

  it('does not connect when there is no token', () => {
    renderHook(() => useWebSocket(null, {}));

    expect(connectWebSocket).not.toHaveBeenCalled();
  });
});