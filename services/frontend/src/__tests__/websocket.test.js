import { describe, it, expect, vi, beforeEach } from 'vitest';

const { mockClientInstance } = vi.hoisted(() => {
  const mockClientInstance = {
    active: false,
    activate: vi.fn(),
    deactivate: vi.fn(),
    subscribe: vi.fn(),
    publish: vi.fn(),
  };
  return { mockClientInstance };
});

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn(() => mockClientInstance),
}));

vi.mock('sockjs-client', () => ({
  default: vi.fn(),
}));

import { Client } from '@stomp/stompjs';
import { connectWebSocket, disconnectWebSocket } from '../lib/websocket';

describe('websocket.js', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockClientInstance.active = false;
    disconnectWebSocket();
  });

  it('sends the access token in STOMP connect headers on every handshake', () => {
    connectWebSocket('test-access-token');

    expect(Client).toHaveBeenCalledTimes(1);
    const config = Client.mock.calls[0][0];
    expect(config.connectHeaders.Authorization).toBe('Bearer test-access-token');
    expect(mockClientInstance.activate).toHaveBeenCalledTimes(1);
  });

  it('keeps reconnect delay at 5s and heartbeat at 4s', () => {
    connectWebSocket('test-access-token');

    const config = Client.mock.calls[0][0];
    expect(config.reconnectDelay).toBe(5000);
    expect(config.heartbeatIncoming).toBe(4000);
    expect(config.heartbeatOutgoing).toBe(4000);
  });

  it('disconnect clears the client so a new connection uses fresh headers', () => {
    connectWebSocket('token-a');
    disconnectWebSocket();
    connectWebSocket('token-b');

    expect(Client).toHaveBeenCalledTimes(2);
    expect(Client.mock.calls[1][0].connectHeaders.Authorization).toBe('Bearer token-b');
  });
});