import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ReplyPanel from '../components/ReplyPanel';
import { api } from '../lib/api';

vi.mock('../lib/api', () => ({
  api: {
    rewrite: { improve: vi.fn() },
    emails: { send: vi.fn() },
  },
}));

const mockEmail = {
  provider: 'google',
  providerEmailId: 'provider-email-1',
  subject: 'Re: Factura',
  fromAddress: 'cliente@example.com',
  bodyPreview: 'Hola, necesito ayuda con mi factura.',
};

describe('ReplyPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('sends the rewritten text when Enviar is clicked', async () => {
    api.rewrite.improve.mockResolvedValue({ rewritten: 'Texto mejorado por IA.' });
    api.emails.send.mockResolvedValue({ status: 'sent', messageId: 'msg-1' });

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador original' } });
    fireEvent.click(screen.getByText('Mejorar con IA'));

    await waitFor(() => {
      expect(screen.getByText('Texto mejorado por IA.')).toBeTruthy();
    });

    fireEvent.click(screen.getByText('Enviar'));

    await waitFor(() => {
      expect(api.emails.send).toHaveBeenCalledWith('google', 'provider-email-1', 'Texto mejorado por IA.');
    });
    expect(await screen.findByText('✓ Enviado')).toBeTruthy();
  });

  it('sends the raw draft when there is no rewritten text', async () => {
    api.emails.send.mockResolvedValue({ status: 'sent', messageId: 'msg-2' });

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    await waitFor(() => {
      expect(api.emails.send).toHaveBeenCalledWith('google', 'provider-email-1', 'Borrador directo');
    });
  });

  it('shows an error when the send fails', async () => {
    api.emails.send.mockRejectedValue(new Error('Network error'));

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    expect(await screen.findByText('Error al enviar. Intentá de nuevo.')).toBeTruthy();
  });
});