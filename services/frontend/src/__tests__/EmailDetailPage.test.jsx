import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import EmailDetailPage from '../pages/EmailDetailPage';
import { api } from '../lib/api';

vi.mock('../lib/api', () => ({
  api: {
    emails: {
      get: vi.fn(),
      action: vi.fn(),
    },
  },
}));

const EMAIL = {
  id: 'e1',
  provider: 'google',
  providerEmailId: 'm1',
  subject: 'Factura de octubre',
  fromAddress: 'a@x.com',
  visto: true,
  bodyHtml: '<p>Hola</p>',
};

function renderAt(path, state) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      <Routes>
        <Route path="/email/:id" element={<EmailDetailPage />} />
      </Routes>
    </MemoryRouter>
  );
}

const OTHER = { ...EMAIL, id: 'e2', providerEmailId: 'm2', subject: 'Otro asunto' };

function GoToOther() {
  const navigate = useNavigate();
  return (
    <button onClick={() => navigate('/email/e2', { state: { email: OTHER } })}>
      ir al otro
    </button>
  );
}

describe('EmailDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.emails.get.mockResolvedValue(EMAIL);
  });

  it('abre el email que llega desde el asistente', async () => {
    renderAt('/email/e1', { email: EMAIL });

    expect(await screen.findByText('Factura de octubre')).toBeTruthy();
  });

  it('busca el email cuando se entra por URL directa', async () => {
    renderAt('/email/e1');

    expect(await screen.findByText('Factura de octubre')).toBeTruthy();
    expect(api.emails.get).toHaveBeenCalledWith('e1');
  });

  it('muestra el email nuevo si ya había otro abierto', async () => {
    api.emails.get.mockImplementation((id) => Promise.resolve(id === 'e2' ? OTHER : EMAIL));
    render(
      <MemoryRouter initialEntries={[{ pathname: '/email/e1', state: { email: EMAIL } }]}>
        <GoToOther />
        <Routes>
          <Route path="/email/:id" element={<EmailDetailPage />} />
        </Routes>
      </MemoryRouter>
    );

    expect(await screen.findByText('Factura de octubre')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'ir al otro' }));
    expect(await screen.findByText('Otro asunto')).toBeTruthy();
  });
});
