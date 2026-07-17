import { useEffect, useRef } from 'react';
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToUser,
  unsubscribe,
} from '../lib/websocket';

function extractUserId(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    return payload.sub || '';
  } catch {
    return '';
  }
}

export function useWebSocket(token, handlers = {}) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!token) return;

    const userId = extractUserId(token);
    if (!userId) return;

    const client = connectWebSocket(token, {
      onConnect: () => {
        if (handlersRef.current.onEmails) {
          subscribeToUser(userId, 'queue/emails', (data) => {
            handlersRef.current.onEmails(data);
          });
        }
        if (handlersRef.current.onChat) {
          subscribeToUser(userId, 'queue/chat', (data) => {
            handlersRef.current.onChat(data);
          });
        }
        if (handlersRef.current.onNotifications) {
          subscribeToUser(userId, 'queue/notifications', (data) => {
            handlersRef.current.onNotifications(data);
          });
        }
      },
      onError: (frame) => {
        if (handlersRef.current.onError) handlersRef.current.onError(frame);
      },
    });

    return () => {
      unsubscribe(`/user/${userId}/queue/emails`);
      unsubscribe(`/user/${userId}/queue/chat`);
      unsubscribe(`/user/${userId}/queue/notifications`);
      disconnectWebSocket();
    };
  }, [token]);
}
