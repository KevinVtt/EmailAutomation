import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useState } from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import ChatPanel from '../components/ChatPanel';

describe('ChatPanel component', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-token');
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      text: () => Promise.resolve(JSON.stringify({
        response: 'Showing important emails.',
        criteria: { important: 'true' },
        conversationId: 'conv-123',
      })),
    });
  });

  it('renders welcome message', () => {
    render(<ChatPanel onFiltersApplied={() => {}} />);
    expect(screen.getByText(/Puedes pedirme que filtre tus emails/i)).toBeTruthy();
  });

  it('renders input field and send button', () => {
    render(<ChatPanel onFiltersApplied={() => {}} />);
    expect(screen.getByPlaceholderText(/Escribe un filtro/i)).toBeTruthy();
    expect(screen.getByRole('button')).toBeTruthy();
  });

  it('sends message and displays response', async () => {
    const onFiltersApplied = vi.fn();
    render(<ChatPanel onFiltersApplied={onFiltersApplied} />);

    const input = screen.getByPlaceholderText(/Escribe un filtro/i);
    fireEvent.change(input, { target: { value: 'Show important emails' } });
    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => {
      expect(screen.getByText('Showing important emails.')).toBeTruthy();
    });
  });

  it('does not duplicate a response already received over WebSocket', async () => {
    const payload = { response: 'Misma respuesta', conversation_id: 'c1', conversationId: 'c1' };
    let resolveFetch;
    global.fetch = vi.fn(() => new Promise((resolve) => { resolveFetch = resolve; }));

    function Harness() {
      const [ws, setWs] = useState(null);
      return (
        <>
          <button onClick={() => setWs(payload)}>push-ws</button>
          <ChatPanel chatResponse={ws} onChatConsumed={() => setWs(null)} />
        </>
      );
    }

    render(<Harness />);
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'hola' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));

    // El push llega antes que el REST: muestra la respuesta una vez.
    fireEvent.click(screen.getByText('push-ws'));
    await waitFor(() => expect(screen.getAllByText('Misma respuesta')).toHaveLength(1));

    await act(async () => {
      resolveFetch({
        ok: true,
        status: 200,
        headers: new Headers({ 'content-type': 'application/json' }),
        text: () => Promise.resolve(JSON.stringify(payload)),
      });
    });

    // El REST no la agrega de nuevo.
    expect(screen.getAllByText('Misma respuesta')).toHaveLength(1);
  });
});
