import { useEffect, useRef } from 'react';
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  unsubscribe,
} from '../lib/websocket';

// The backend resolves the authenticated user from the STOMP session, so user
// destinations must not carry a userId in the path.
const USER_TOPICS = {
  emails: '/user/queue/emails',
  chat: '/user/queue/chat',
  notifications: '/user/queue/notifications',
};

export function useWebSocket(token, handlers = {}) {
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!token) return;

    const client = connectWebSocket(token, {
      onConnect: () => {
        if (handlersRef.current.onEmails) {
          subscribeToTopic(USER_TOPICS.emails, (data) => {
            handlersRef.current.onEmails(data);
          });
        }
        if (handlersRef.current.onChat) {
          subscribeToTopic(USER_TOPICS.chat, (data) => {
            handlersRef.current.onChat(data);
          });
        }
        if (handlersRef.current.onNotifications) {
          subscribeToTopic(USER_TOPICS.notifications, (data) => {
            handlersRef.current.onNotifications(data);
          });
        }
      },
      onError: (frame) => {
        if (handlersRef.current.onError) handlersRef.current.onError(frame);
      },
    });

    return () => {
      unsubscribe(USER_TOPICS.emails);
      unsubscribe(USER_TOPICS.chat);
      unsubscribe(USER_TOPICS.notifications);
      disconnectWebSocket();
    };
  }, [token]);
}
