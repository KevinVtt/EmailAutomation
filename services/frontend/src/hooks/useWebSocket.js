import { useEffect, useRef } from 'react';
import {
  connectWebSocket,
  onWebSocketConnect,
  onWebSocketError,
  subscribeToTopic,
} from '../lib/websocket';

// The backend resolves the authenticated user from the STOMP session, so user
// destinations must not carry a userId in the path.
const USER_TOPICS = {
  emails: '/user/queue/emails',
  chat: '/user/queue/chat',
  notifications: '/user/queue/notifications',
};

const HANDLER_TOPICS = [
  ['onEmails', USER_TOPICS.emails],
  ['onChat', USER_TOPICS.chat],
  ['onNotifications', USER_TOPICS.notifications],
];

export function useWebSocket(token, handlers = {}) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!token) return;

    connectWebSocket(token);

    const offSubscriptions = HANDLER_TOPICS
      .filter(([key]) => handlersRef.current[key])
      .map(([key, topic]) => subscribeToTopic(topic, (data) => handlersRef.current[key]?.(data)));

    const offConnect = handlersRef.current.onConnect
      ? onWebSocketConnect(() => handlersRef.current.onConnect?.())
      : null;
    const offError = handlersRef.current.onError
      ? onWebSocketError((frame) => handlersRef.current.onError?.(frame))
      : null;

    // La conexión queda viva al desmontar (es compartida entre páginas); sólo se
    // dan de baja las suscripciones propias. El cierre va en el logout.
    return () => {
      offSubscriptions.forEach((off) => off());
      offConnect?.();
      offError?.();
    };
  }, [token]);
}
