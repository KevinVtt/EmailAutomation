import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';

const EMPTY_FORM = { address: '', displayName: '', phone: '', company: '', topic: '', notes: '' };

function ContactForm({ initial, onSave, onCancel, isNew }) {
  const [form, setForm] = useState(initial);
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const { address, ...fields } = form;
      await onSave(isNew ? address.trim() : null, fields);
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="grid gap-2 sm:grid-cols-2 p-3 border-t border-gray-200 dark:border-gray-700">
      {isNew && (
        <input className="input sm:col-span-2" type="email" required placeholder="Email"
          aria-label="Email" value={form.address} onChange={set('address')} />
      )}
      <input className="input" placeholder="Nombre" aria-label="Nombre" value={form.displayName} onChange={set('displayName')} />
      <input className="input" placeholder="Teléfono" aria-label="Teléfono" value={form.phone} onChange={set('phone')} />
      <input className="input" placeholder="Empresa" aria-label="Empresa" value={form.company} onChange={set('company')} />
      <input className="input" placeholder="Tema" aria-label="Tema" value={form.topic} onChange={set('topic')} />
      <textarea className="input sm:col-span-2" placeholder="Notas" aria-label="Notas" rows={2} value={form.notes} onChange={set('notes')} />
      {error && <p className="sm:col-span-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
      <div className="sm:col-span-2 flex gap-2 justify-end">
        <button type="button" onClick={onCancel} className="text-sm text-gray-500 hover:text-gray-800 dark:text-gray-400">Cancelar</button>
        <button type="submit" disabled={saving} className="btn-primary text-sm">{saving ? 'Guardando…' : 'Guardar'}</button>
      </div>
    </form>
  );
}

export default function Contacts() {
  const [contacts, setContacts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [query, setQuery] = useState('');
  const [editingId, setEditingId] = useState(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const all = [];
      let batch;
      let page = 0;
      do {
        batch = await api.contacts.list(100, page++);
        all.push(...batch);
      // El tope corta el bucle si el backend ignora `page` (peticiones infinitas).
      } while (batch.length === 100 && page < 50);
      setContacts(all);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return contacts;
    return contacts.filter((c) =>
      [c.address, c.displayName, c.company, c.phone].some((v) => v && v.toLowerCase().includes(q)));
  }, [contacts, query]);

  const saveNew = async (address, fields) => {
    const created = await api.contacts.create(address, fields);
    setCreating(false);
    setContacts((current) => [created, ...current]);
  };

  const saveEdit = async (_address, fields) => {
    await api.contacts.update(editingId, fields);
    setEditingId(null);
    await load();
  };

  const remove = async (contact) => {
    if (!window.confirm(`¿Eliminar a ${contact.address} de tus contactos?`)) return;
    try {
      await api.contacts.remove(contact.id);
      await load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="p-4 sm:p-6 max-w-3xl mx-auto space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-gray-900 dark:text-gray-100">Contactos</h1>
        <button type="button" onClick={() => setCreating(true)} className="btn-primary text-sm">Nuevo contacto</button>
      </div>

      <input className="input w-full" type="search" placeholder="Buscar por nombre, email, empresa o teléfono"
        aria-label="Buscar contactos" value={query} onChange={(e) => setQuery(e.target.value)} />

      {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
      {creating && (
        <div className="card">
          <ContactForm initial={EMPTY_FORM} isNew onSave={saveNew} onCancel={() => setCreating(false)} />
        </div>
      )}

      <div className="card divide-y divide-gray-200 dark:divide-gray-700">
        {loading && <p className="p-3 text-sm text-gray-500">Cargando…</p>}
        {!loading && visible.length === 0 && <p className="p-3 text-sm text-gray-500">No hay contactos.</p>}
        {visible.map((c) => (
          <div key={c.id}>
            <div className="flex items-center justify-between gap-3 p-3">
              <div className="min-w-0">
                <p className="font-medium text-sm text-gray-900 dark:text-gray-100 truncate">{c.displayName || c.address}</p>
                <p className="text-xs text-gray-500 dark:text-gray-400 truncate">
                  {[c.address, c.phone, c.company].filter(Boolean).join(' · ')}
                </p>
              </div>
              <div className="flex gap-3 flex-shrink-0 text-sm">
                <button type="button" onClick={() => setEditingId(editingId === c.id ? null : c.id)} className="text-primary-700 dark:text-primary-300 hover:underline">
                  Editar
                </button>
                <button type="button" onClick={() => remove(c)} className="text-red-600 dark:text-red-400 hover:underline">
                  Eliminar
                </button>
              </div>
            </div>
            {editingId === c.id && (
              <ContactForm
                initial={{ address: c.address, displayName: c.displayName || '', phone: c.phone || '',
                  company: c.company || '', topic: c.topic || '', notes: c.notes || '' }}
                onSave={saveEdit}
                onCancel={() => setEditingId(null)}
              />
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
