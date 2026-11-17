import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import Contacts from '../pages/Contacts';

vi.mock('../lib/api', () => ({
  api: {
    contacts: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      remove: vi.fn(),
    },
  },
}));

import { api } from '../lib/api';

const CONTACTS = [
  { id: '1', address: 'ana@x.com', displayName: 'Ana', company: 'Acme', phone: '', topic: '', notes: '' },
  { id: '2', address: 'bruno@y.com', displayName: 'Bruno', company: '', phone: '+54 11', topic: '', notes: '' },
];

describe('Contacts page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.contacts.list.mockResolvedValue(CONTACTS);
  });

  it('filters the list by name, email, company or phone', async () => {
    render(<Contacts />);
    await screen.findByText('Ana');

    fireEvent.change(screen.getByLabelText('Buscar contactos'), { target: { value: '+54' } });

    expect(screen.queryByText('Ana')).toBeNull();
    expect(screen.getByText('Bruno')).toBeTruthy();
  });

  it('shows a newly created contact beyond the first 100, including after reopening', async () => {
    const active = Array.from({ length: 100 }, (_, i) => ({ id: `active-${i}`, address: `active${i}@example.com` }));
    const created = { id: 'new', address: 'nuevo@example.com', displayName: 'Nuevo contacto guardado' };
    let saved = false;
    api.contacts.list.mockImplementation(async (_limit, page = 0) => page === 0 ? active : saved ? [created] : []);
    api.contacts.create.mockImplementation(async () => { saved = true; return created; });
    const view = render(<Contacts />);
    await screen.findByText('active0@example.com', { selector: 'p.font-medium' });
    fireEvent.click(screen.getByText('Nuevo contacto'));
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: created.address } });
    fireEvent.change(screen.getByLabelText('Nombre'), { target: { value: created.displayName } });
    fireEvent.click(screen.getByText('Guardar'));
    await screen.findByText(created.displayName);
    expect(api.contacts.create).toHaveBeenCalledWith(created.address, expect.objectContaining({ displayName: created.displayName }));
    view.unmount();
    render(<Contacts />);
    await screen.findByText(created.displayName);
  });

  it('stops paging when the backend ignores the page parameter', async () => {
    let call = 0;
    api.contacts.list.mockImplementation(async () => {
      call += 1;
      return Array.from({ length: 100 }, (_, i) => ({ id: `c${call}-${i}`, address: `same${i}@example.com` }));
    });
    render(<Contacts />);
    await waitFor(() => expect(api.contacts.list).toHaveBeenCalledTimes(50), { timeout: 20000 });
    expect(screen.queryByText('Cargando…')).toBeNull();
  });

  it('removes a contact only after confirmation', async () => {
    api.contacts.remove.mockResolvedValue(null);
    vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<Contacts />);
    await screen.findByText('Ana');

    const [firstDelete] = screen.getAllByText('Eliminar');
    fireEvent.click(firstDelete);
    expect(api.contacts.remove).not.toHaveBeenCalled();

    fireEvent.click(firstDelete);
    await waitFor(() => expect(api.contacts.remove).toHaveBeenCalledWith('1'));
  });
});
