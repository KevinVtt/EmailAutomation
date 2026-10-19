import { useState } from 'react';
import { ArrowLeft, Bot, Send } from 'lucide-react';
import { api } from '../../lib/api';

export default function ComposeForm({ contact, onBack }) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [instruction, setInstruction] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [sent, setSent] = useState(false);

  const canSend = subject.trim() !== '' && body.trim() !== '' && !sending;

  const draft = async () => {
    setError('');
    setDrafting(true);
    try {
      const result = await api.contacts.draft(contact.id, instruction);
      if (result?.subject) setSubject(result.subject);
      if (result?.body) setBody(result.body);
    } catch {
      setError('No se pudo redactar el mail. Probá de nuevo.');
    } finally {
      setDrafting(false);
    }
  };

  const send = async () => {
    setError('');
    setSending(true);
    try {
      await api.emails.compose({ to: contact.address, subject, body });
      setSent(true);
      setConfirming(false);
    } catch (e) {
      // El borrador queda intacto: el usuario puede reintentar sin reescribirlo.
      setError(e.message || 'No se pudo enviar el mail.');
    } finally {
      setSending(false);
    }
  };

  if (sent) {
    return (
      <div className="flex flex-col gap-3 p-1">
        <p className="text-sm text-gray-800 dark:text-gray-200">
          Mail enviado a {contact.address}.
        </p>
        <button
          type="button"
          onClick={() => {
            setSent(false);
            setSubject('');
            setBody('');
          }}
          className="self-start text-sm text-primary-700 dark:text-primary-300"
        >
          Escribir otro
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 flex-1 min-h-0 overflow-y-auto p-1">
      <div className="flex items-center gap-2">
        <button
          type="button"
          aria-label="Volver a contactos"
          onClick={onBack}
          className="p-1 text-gray-500 hover:text-gray-800 dark:text-gray-400"
        >
          <ArrowLeft className="w-4 h-4" />
        </button>
        <div className="min-w-0">
          <p className="text-sm font-medium text-gray-800 dark:text-gray-200 truncate">
            {contact.displayName || 'Contacto'}
          </p>
          <p className="text-xs text-gray-500 dark:text-gray-400 truncate">
            {[contact.company, contact.topic].filter(Boolean).join(' · ') || 'Sin ficha'}
          </p>
        </div>
      </div>

      {contact.notes && (
        <p className="text-xs text-gray-600 dark:text-gray-300 bg-gray-50 dark:bg-gray-700 rounded px-2 py-1">
          {contact.notes}
        </p>
      )}

      <div className="flex gap-1">
        <input
          type="text"
          value={instruction}
          onChange={(e) => setInstruction(e.target.value)}
          aria-label="Qué le querés decir"
          placeholder="Ej: mandale la factura de octubre"
          className="input flex-1 text-sm dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100"
        />
        <button
          type="button"
          onClick={draft}
          disabled={!instruction.trim() || drafting}
          className="btn-primary px-2 text-xs flex items-center gap-1"
        >
          <Bot className="w-4 h-4" />
          Redactar con IA
        </button>
      </div>

      <label htmlFor="compose-subject" className="text-xs text-gray-600 dark:text-gray-300">
        Asunto
      </label>
      <input
        id="compose-subject"
        type="text"
        value={subject}
        onChange={(e) => setSubject(e.target.value)}
        className="input text-sm dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100"
      />

      <label htmlFor="compose-body" className="text-xs text-gray-600 dark:text-gray-300">
        Cuerpo
      </label>
      <textarea
        id="compose-body"
        value={body}
        onChange={(e) => setBody(e.target.value)}
        rows={7}
        className="input text-sm dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100"
      />

      {error && <p className="text-xs text-red-600 dark:text-red-400">{error}</p>}

      {confirming ? (
        <div className="rounded-lg border border-primary-200 dark:border-gray-600 p-2 space-y-1 text-sm">
          <p className="font-medium text-gray-800 dark:text-gray-200">Confirmá el envío</p>
          <p className="text-gray-700 dark:text-gray-300 break-all">Para: {contact.address}</p>
          <p className="text-gray-700 dark:text-gray-300 break-words">Asunto: {subject}</p>
          <div className="flex gap-2 pt-1">
            <button
              type="button"
              onClick={send}
              disabled={sending}
              className="btn-primary text-sm px-3 py-1"
            >
              Confirmar envío
            </button>
            <button
              type="button"
              onClick={() => setConfirming(false)}
              className="text-sm text-gray-600 dark:text-gray-300"
            >
              Volver
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          disabled={!canSend}
          className="btn-primary self-start px-3 py-1 text-sm flex items-center gap-1"
        >
          <Send className="w-4 h-4" />
          Enviar
        </button>
      )}
    </div>
  );
}
