import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
});
