import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ComposeForm from '../components/assistant/ComposeForm';

const CONTACT = {
  id: 'c1',
  address: 'a@x.com',
  displayName: 'Ana',
  company: 'Acme',
  topic: 'facturas',
  notes: 'paga los viernes',
  sentCount: 1,
  receivedCount: 2,
};

function jsonResponse(payload) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    text: () => Promise.resolve(JSON.stringify(payload)),
  };
}

const callsTo = (path) =>
  global.fetch.mock.calls.filter(([url]) => String(url).includes(path));

describe('ComposeForm', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-token');
    global.fetch = vi.fn((url) => {
      if (String(url).includes('/draft')) {
        return Promise.resolve(jsonResponse({ subject: 'Factura de octubre', body: 'Hola Ana' }));
      }
      return Promise.resolve(jsonResponse({ status: 'sent', messageId: 'm1' }));
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('fills subject and body with the AI draft', async () => {
    render(<ComposeForm contact={CONTACT} />);

    fireEvent.change(screen.getByLabelText(/Qué le querés decir/i), {
      target: { value: 'mandale la factura' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Redactar con IA/i }));

    await waitFor(() => expect(screen.getByLabelText(/Asunto/i).value).toBe('Factura de octubre'));
    expect(screen.getByLabelText(/Cuerpo/i).value).toBe('Hola Ana');
  });

  it('does not send anything until the confirmation is accepted', async () => {
    render(<ComposeForm contact={CONTACT} />);

    fireEvent.change(screen.getByLabelText(/Asunto/i), { target: { value: 'Asunto exacto' } });
    fireEvent.change(screen.getByLabelText(/Cuerpo/i), { target: { value: 'Cuerpo exacto' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));

    expect(callsTo('/api/emails/compose')).toHaveLength(0);
    expect(screen.getByText(/a@x\.com/)).toBeTruthy();
    expect(screen.getByText(/Asunto exacto/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /Confirmar envío/i }));

    await waitFor(() => expect(callsTo('/api/emails/compose')).toHaveLength(1));
    const body = JSON.parse(callsTo('/api/emails/compose')[0][1].body);
    expect(body).toEqual({ to: 'a@x.com', subject: 'Asunto exacto', body: 'Cuerpo exacto' });
  });

  it('keeps the draft and shows the reason when the backend rejects the send', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 500,
        headers: new Headers({ 'content-type': 'text/plain' }),
        text: () => Promise.resolve('Gmail rechazó el envío'),
      }));

    render(<ComposeForm contact={CONTACT} />);
    fireEvent.change(screen.getByLabelText(/Asunto/i), { target: { value: 'Asunto exacto' } });
    fireEvent.change(screen.getByLabelText(/Cuerpo/i), { target: { value: 'Cuerpo exacto' } });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    fireEvent.click(screen.getByRole('button', { name: /Confirmar envío/i }));

    await waitFor(() => expect(screen.getByText('Gmail rechazó el envío')).toBeTruthy());
    expect(screen.getByLabelText(/Asunto/i).value).toBe('Asunto exacto');
    expect(screen.getByLabelText(/Cuerpo/i).value).toBe('Cuerpo exacto');
  });
});
