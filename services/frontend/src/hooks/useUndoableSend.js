import { useState, useCallback } from 'react';

/**
 * Gmail-style "undo send": the send is queued for `delayMs` instead of firing
 * immediately, giving the user a window to cancel. The queued send still fires
 * even if the component unmounts (the user clicked send — the email goes out).
 *
 * @param {Function} sendFn  async function receiving the payload
 * @param {number}   delayMs milliseconds to wait before calling sendFn
 * @returns {{ queueSend: (payload: any) => void, cancel: () => void, isPending: boolean }}
 */
export function useUndoableSend(sendFn, delayMs = 5000) {
  const [pending, setPending] = useState(null); // { payload, timeoutId }

  const queueSend = useCallback(
    (payload) => {
      const timeoutId = setTimeout(() => {
        sendFn(payload);
        setPending(null);
      }, delayMs);
      setPending({ payload, timeoutId });
    },
    [sendFn, delayMs]
  );

  const cancel = useCallback(() => {
    if (pending) {
      clearTimeout(pending.timeoutId);
      setPending(null);
    }
  }, [pending]);

  return { queueSend, cancel, isPending: !!pending };
}