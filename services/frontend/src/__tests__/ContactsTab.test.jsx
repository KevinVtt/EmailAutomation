import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import ContactsTab from '../components/assistant/ContactsTab';

const CONTACTS = [
  {
    id: 'c2', address: 'b@x.com', displayName: 'Beto', company: 'Beta',
    topic: 'pagos', notes: '', sentCount: 5, receivedCount: 7,
  },
  {
    id: 'c1', address: 'a@x.com', displayName: 'Ana', company: '',
    topic: '', notes: '', sentCount: 0, receivedCount: 2,
  },
];

function jsonResponse(payload) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    text: () => Promise.resolve(JSON.stringify(payload)),
  };
}

describe('ContactsTab', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'test-token');
    global.fetch = vi.fn((url) => {
      if (String(url).includes('/enrich')) {
        return Promise.resolve(jsonResponse({ ...CONTACTS[1], company: 'Acme', topic: 'facturas' }));
      }
      if (String(url).includes('/draft')) {
        return Promise.resolve(jsonResponse({ subject: 'Factura', body: 'Hola Ana' }));
      }
      return Promise.resolve(jsonResponse(CONTACTS));
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('lists address, company and the total traffic in the order the API gives', async () => {
    render(<ContactsTab />);

    await waitFor(() => expect(screen.getByText(/b@x\.com/)).toBeTruthy());

    const rows = screen.getAllByTestId('contact-row');
    expect(rows[0].textContent).toContain('b@x.com');
    expect(rows[0].textContent).toContain('Beta');
    expect(rows[0].textContent).toContain('12');
    expect(rows[1].textContent).toContain('a@x.com');
    expect(rows[1].textContent).toContain('2');
  });

  it('enriches a contact without company once and shows what came back', async () => {
    render(<ContactsTab />);
    await waitFor(() => expect(screen.getByText(/a@x\.com/)).toBeTruthy());

    fireEvent.click(screen.getByText(/a@x\.com/));

    await waitFor(() => expect(screen.getByText(/Acme/)).toBeTruthy());
    const enrichCalls = global.fetch.mock.calls.filter(([url]) =>
      String(url).includes('/enrich'));
    expect(enrichCalls).toHaveLength(1);
  });
});
