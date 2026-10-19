import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import AssistantPanel from '../components/assistant/AssistantPanel';
import { ScreenScopeProvider } from '../context/ScreenScopeContext';
import Spam from '../pages/Spam';

vi.mock('../components/EmailListView', () => ({
  default: () => <div>email-list</div>,
}));

const EMAILS = [
  { id: 'e1', providerEmailId: 'm1', provider: 'google', subject: 'Primera', fromAddress: 'a@x.com' },
  { id: 'e2', providerEmailId: 'm2', provider: 'google', subject: 'Segunda', fromAddress: 'b@x.com' },
];

function jsonResponse(payload) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    text: () => Promise.resolve(JSON.stringify(payload)),
  };
}

describe('AssistantPanel', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-token');
    global.fetch = vi.fn((url) => {
      if (String(url).includes('/api/chat')) {
        return Promise.resolve(jsonResponse({
          response: 'Encontré 2 mails.',
          criteria: { label: 'SPAM' },
          conversation_id: 'c1',
          emails: EMAILS,
        }));
      }
      return Promise.resolve(jsonResponse({ applied: 1, failed: [] }));
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const renderPanel = (children = null) =>
    render(
      <MemoryRouter>
        <ScreenScopeProvider>
          {children}
          <AssistantPanel />
        </ScreenScopeProvider>
      </MemoryRouter>
    );

  it('shows a floating button and starts closed', () => {
    renderPanel();

    expect(screen.getByRole('button', { name: 'Asistente' })).toBeTruthy();
    expect(screen.getByTestId('assistant-panel')).toHaveAttribute('data-open', 'false');
  });

  it('opens over the page without unmounting it', () => {
    renderPanel(<div data-testid="page-content">lista</div>);

    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));

    expect(screen.getByTestId('assistant-panel')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('page-content')).toBeTruthy();
  });

  it('sends the Spam scope with the message', async () => {
    renderPanel(<Spam />);

    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'cuáles tengo' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));

    await waitFor(() => expect(screen.getByText('Encontré 2 mails.')).toBeTruthy());

    const chatCall = global.fetch.mock.calls.find(([url]) => String(url).includes('/api/chat'));
    const payload = JSON.parse(chatCall[1].body);
    expect(payload.scope).toEqual({ folder: 'spam', criteria: { label: 'SPAM' }, description: 'Spam' });
  });

  it('no muestra el chip de alcance cuando la lista no restringe', () => {
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));

    expect(screen.queryByText('Buscar en todo')).toBeNull();
  });

  it('permite buscar en toda la cuenta desde una lista que restringe', async () => {
    renderPanel(<Spam />);
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));

    expect(screen.getByText('Buscando solo en Spam')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Buscar en todo' }));
    expect(screen.getByText('Buscando en toda la cuenta')).toBeTruthy();

    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'el mail de la factura' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));

    await waitFor(() => expect(screen.getByText('Encontré 2 mails.')).toBeTruthy());
    const chatCall = global.fetch.mock.calls.find(([url]) => String(url).includes('/api/chat'));
    expect(JSON.parse(chatCall[1].body).scope).toBeUndefined();
  });

  it('abre el email del resultado en su propia vista', async () => {
    renderPanel(
      <Routes>
        <Route path="/email/:id" element={<div>vista-email</div>} />
      </Routes>
    );

    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'cuáles tengo' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(screen.getByText('Primera')).toBeTruthy());

    fireEvent.click(screen.getByText('Primera'));

    expect(await screen.findByText('vista-email')).toBeTruthy();
    expect(screen.getByTestId('assistant-panel')).toHaveAttribute('data-open', 'false');
  });

  it('usa un textarea: Enter envía y Shift+Enter agrega una línea', async () => {
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));

    const box = screen.getByPlaceholderText(/Escribe un filtro/i);
    expect(box.tagName).toBe('TEXTAREA');

    fireEvent.change(box, { target: { value: 'una consulta larga' } });
    fireEvent.keyDown(box, { key: 'Enter', shiftKey: true });
    expect(global.fetch).not.toHaveBeenCalled();

    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(screen.getByText('Encontré 2 mails.')).toBeTruthy());
  });

  it('expande el panel al hacer foco en el campo y lo contrae con el botón', () => {
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));

    const panel = screen.getByTestId('assistant-panel');
    expect(panel).toHaveAttribute('data-expanded', 'false');

    fireEvent.focus(screen.getByPlaceholderText(/Escribe un filtro/i));
    expect(panel).toHaveAttribute('data-expanded', 'true');

    fireEvent.click(screen.getByRole('button', { name: 'Contraer asistente' }));
    expect(panel).toHaveAttribute('data-expanded', 'false');
  });

  it('vuelve a tamaño chico al cerrar y abrir', () => {
    renderPanel();
    const trigger = screen.getByRole('button', { name: 'Asistente' });
    fireEvent.click(trigger);
    fireEvent.focus(screen.getByPlaceholderText(/Escribe un filtro/i));

    const panel = screen.getByTestId('assistant-panel');
    expect(panel).toHaveAttribute('data-expanded', 'true');

    fireEvent.click(screen.getByRole('button', { name: 'Cerrar asistente' }));
    fireEvent.click(trigger);

    expect(panel).toHaveAttribute('data-open', 'true');
    expect(panel).toHaveAttribute('data-expanded', 'false');
  });

  it('limpia el filtro y la conversación para volver a empezar', async () => {
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'el mail de la factura' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(screen.getByText('Primera')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Limpiar filtro' }));

    expect(screen.queryByText('Primera')).toBeNull();
    expect(screen.getByText(/Puedes pedirme que filtre/i)).toBeTruthy();

    // La próxima consulta arranca un hilo nuevo, sin el criterio anterior.
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'otra cosa' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
    expect(JSON.parse(global.fetch.mock.calls[1][1].body).conversationId).toBe('');
  });

  it('deja limpiar el filtro aunque no haya resultados', async () => {
    global.fetch = vi.fn().mockResolvedValue(jsonResponse({
      response: 'No encontré mails.',
      criteria: { fromAddress: 'nadie@x.com' },
      conversation_id: 'c9',
      emails: [],
    }));
    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'correos de nadie' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));

    expect(await screen.findByText('Sin resultados')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Limpiar filtro' })).toBeTruthy();
  });

  it('keeps the conversation when it is closed and reopened', async () => {    renderPanel();

    const toggle = screen.getByRole('button', { name: 'Asistente' });
    fireEvent.click(toggle);
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'hola' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(screen.getByText('Encontré 2 mails.')).toBeTruthy());

    fireEvent.click(toggle);
    fireEvent.click(toggle);

    expect(screen.getByText('Encontré 2 mails.')).toBeTruthy();
  });

  it('stars a returned mail in one call', async () => {
    renderPanel();

    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'cuáles tengo' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(screen.getByText('Primera')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Destacar Primera' }));

    await waitFor(() => {
      const call = global.fetch.mock.calls.find(([url]) =>
        String(url).includes('/api/emails/bulk-action'));
      expect(call).toBeTruthy();
      expect(JSON.parse(call[1].body)).toEqual({
        provider: 'google',
        emailIds: ['m1'],
        action: 'star',
      });
    });
  });

  it('asks before moving a mail to the trash', async () => {
    const confirm = vi.fn(() => false);
    window.confirm = confirm;

    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Asistente' }));
    fireEvent.change(screen.getByPlaceholderText(/Escribe un filtro/i), {
      target: { value: 'cuáles tengo' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(screen.getByText('Segunda')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: 'Eliminar Segunda' }));

    expect(confirm).toHaveBeenCalled();
    expect(global.fetch.mock.calls.some(([url]) =>
      String(url).includes('/api/emails/bulk-action'))).toBe(false);
  });
});
