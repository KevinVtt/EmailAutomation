import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
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

const UNDO_DELAY_MS = 5000;

describe('ReplyPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends the rewritten text when Enviar is clicked', async () => {
    api.rewrite.improve.mockResolvedValue({ rewritten: 'Texto mejorado por IA.' });
    api.emails.send.mockResolvedValue({ status: 'sent', messageId: 'msg-1' });

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador original' } });
    fireEvent.click(screen.getByText('Mejorar con IA'));

    await act(async () => {});
    expect(screen.getByText('Texto mejorado por IA.')).toBeTruthy();

    fireEvent.click(screen.getByText('Enviar'));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_DELAY_MS);
    });

    expect(api.emails.send).toHaveBeenCalledWith('google', 'provider-email-1', 'Texto mejorado por IA.');
    expect(screen.getByText('✓ Enviado')).toBeTruthy();
  });

  it('sends the raw draft when there is no rewritten text', async () => {
    api.emails.send.mockResolvedValue({ status: 'sent', messageId: 'msg-2' });

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_DELAY_MS);
    });

    expect(api.emails.send).toHaveBeenCalledWith('google', 'provider-email-1', 'Borrador directo');
  });

  it('shows an error when the send fails', async () => {
    api.emails.send.mockRejectedValue(new Error('Network error'));

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_DELAY_MS);
    });

    expect(screen.getByText('Error al enviar. Intentá de nuevo.')).toBeTruthy();
  });

  it('does not send immediately and shows the undo toast', () => {
    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    expect(api.emails.send).not.toHaveBeenCalled();
    expect(screen.getByText('Enviando en unos segundos…')).toBeTruthy();
    expect(screen.getByText('Deshacer')).toBeTruthy();
  });

  it('cancels the queued send when Deshacer is clicked', async () => {
    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    fireEvent.click(screen.getByText('Deshacer'));

    expect(screen.queryByText('Enviando en unos segundos…')).toBeNull();

    // Even after the full delay elapses, the cancelled send never fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_DELAY_MS);
    });

    expect(api.emails.send).not.toHaveBeenCalled();
  });

  it('re-enables the send button after cancelling the queued send', () => {
    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    // While queued, the button shows the spinner and is disabled.
    expect(screen.getByRole('button', { name: /Enviando/ }).disabled).toBe(true);

    fireEvent.click(screen.getByText('Deshacer'));

    // After cancelling, the button returns to its normal "Enviar" state.
    const sendButton = screen.getByRole('button', { name: 'Enviar' });
    expect(sendButton.disabled).toBe(false);
    expect(screen.queryByRole('button', { name: /Enviando/ })).toBeNull();
    expect(api.emails.send).not.toHaveBeenCalled();
  });

  it('sends after the undo delay elapses', async () => {
    api.emails.send.mockResolvedValue({ status: 'sent', messageId: 'msg-3' });

    render(<ReplyPanel email={mockEmail} onClose={() => {}} />);

    const textarea = screen.getByPlaceholderText(/Escribí tu respuesta acá/i);
    fireEvent.change(textarea, { target: { value: 'Borrador directo' } });
    fireEvent.click(screen.getByText('Enviar'));

    expect(api.emails.send).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNDO_DELAY_MS);
    });

    expect(api.emails.send).toHaveBeenCalledWith('google', 'provider-email-1', 'Borrador directo');
    expect(screen.getByText('✓ Enviado')).toBeTruthy();
  });
});