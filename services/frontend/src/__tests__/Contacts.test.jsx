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
