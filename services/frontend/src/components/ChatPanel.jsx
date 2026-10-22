import { useState, useRef, useEffect } from 'react';
import { api } from '../lib/api';
import { Sparkles, Send, Bot, User } from 'lucide-react';
import { LoadingState } from './states';

// El backend empuja cada respuesta por WS y además la devuelve por REST. La clave
// incluye el texto: el conversation_id solo se reusa en todos los turnos del hilo.
const responseKey = (r) => `${r.conversation_id || ''}|${r.response || ''}`;

const GREETING = {
  role: 'assistant',
  content: '¡Hola! Puedes pedirme que filtre tus emails. Por ejemplo:\n\n• "Muéstrame los emails importantes de esta semana"\n• "Busca emails de facturación"\n• "¿Qué emails tengo sin leer de ayer?"',
};

export default function ChatPanel({ onFiltersApplied, chatResponse, onChatConsumed, scope, onResults, resetKey = 0, onExpand, embedded = false }) {
  const [messages, setMessages] = useState([GREETING]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [conversationId, setConversationId] = useState('');
  const messagesEndRef = useRef(null);
  const processedResponses = useRef(new Set());
  // El backend empuja TODA respuesta de chat al mismo queue del usuario: sin este
  // flag el panel se quedaba con la respuesta de un chat enviado desde otra página.
  const pendingRequest = useRef(false);
  const textareaRef = useRef(null);

  // Crece con el texto hasta ~5 líneas; después scrollea.
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(Math.max(el.scrollHeight, 40), 120)}px`;
  }, [input]);

  // "Limpiar filtro": vuelve al estado inicial para que la próxima consulta no
  // arrastre el criterio anterior (el backend recuerda el hilo por conversation_id).
  useEffect(() => {
    if (!resetKey) return;
    setMessages([GREETING]);
    setConversationId('');
    setInput('');
    setLoading(false);
    processedResponses.current.clear();
  }, [resetKey]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (!chatResponse || !pendingRequest.current) return;
    const key = responseKey(chatResponse);
    if (processedResponses.current.has(key)) {
      return;
    }

    processedResponses.current.add(key);
    pendingRequest.current = false;
    setMessages((prev) => [
      ...prev,
      { role: 'assistant', content: chatResponse.response },
    ]);
    setLoading(false);
    onChatConsumed?.();

    if (Array.isArray(chatResponse.emails)) onResults?.(chatResponse.emails);

    if (chatResponse.criteria && Object.keys(chatResponse.criteria).length > 0) {
      onFiltersApplied?.(chatResponse.criteria);
    }
  }, [chatResponse, onFiltersApplied, onChatConsumed, onResults]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!input.trim() || loading) {
      return;
    }

    // El dedup vale solo dentro de un turno: un texto corto repetido en otro turno debe mostrarse.
    processedResponses.current.clear();
    const userMessage = input.trim();
    setInput('');
    setMessages((prev) => [...prev, { role: 'user', content: userMessage }]);
    setLoading(true);
    pendingRequest.current = true;

    try {
      const response = await api.chat.send(userMessage, conversationId, scope);
      const cid = response.conversationId || response.conversation_id || conversationId;
      setConversationId(cid);
      // Si el push WS llegó antes que el REST, ya mostró el mensaje y los
      // resultados: appendear acá duplicaba la respuesta del asistente.
      const key = responseKey(response);
      const alreadyProcessed = processedResponses.current.has(key);
      if (!alreadyProcessed) processedResponses.current.add(key);
      setLoading(false);
      if (alreadyProcessed) return;
      if (Array.isArray(response.emails)) onResults?.(response.emails);
      if (response.response) {
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: response.response },
        ]);
      }
      if (response.criteria && Object.keys(response.criteria).length > 0) {
        onFiltersApplied?.(response.criteria);
      }
    } catch (err) {
      // Si el WS ya entregó la respuesta, el error del REST no debe duplicar el aviso.
      if (!pendingRequest.current) return;
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: 'Lo siento, hubo un error al procesar tu mensaje.' },
      ]);
      setLoading(false);
    } finally {
      pendingRequest.current = false;
    }
  };

  return (
    <div className={embedded ? 'flex flex-col h-full' : 'card flex flex-col h-full dark:bg-gray-800 dark:border-gray-700'}>
      {!embedded && (
        <div className="flex items-center gap-2 mb-4 pb-3 border-b border-primary-200 dark:border-gray-700">
          <Bot className="w-5 h-5 text-accent-600" />
          <h2 className="text-lg font-semibold text-gray-900 dark:text-gray-100">Asistente IA</h2>
        </div>
      )}

      <div className={`flex-1 overflow-y-auto space-y-3 mb-4 ${embedded ? '' : '-mx-4 px-4'}`}>
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`flex gap-2 ${msg.role === 'user' ? 'justify-end' : ''}`}
          >
            {msg.role === 'assistant' && (
              <div className="w-8 h-8 rounded-full bg-accent-100 dark:bg-accent-900/40 flex items-center justify-center flex-shrink-0">
                <Sparkles className="w-4 h-4 text-accent-700 dark:text-accent-400" />
              </div>
            )}
            <div
              className={`max-w-[85%] rounded-xl px-3 py-2 text-sm ${
                msg.role === 'user'
                  ? 'bg-primary-700 text-white rounded-br-sm'
                  : 'bg-gray-100 dark:bg-gray-700 text-gray-800 dark:text-gray-200 rounded-bl-sm'
              }`}
            >
              <p className="whitespace-pre-wrap">{msg.content}</p>
            </div>
            {msg.role === 'user' && (
              <div className="w-8 h-8 rounded-full bg-primary-600 flex items-center justify-center flex-shrink-0">
                <User className="w-4 h-4 text-white" />
              </div>
            )}
          </div>
        ))}

        {loading && (
          <div className="flex gap-2">
            <div className="w-8 h-8 rounded-full bg-accent-100 dark:bg-accent-900/40 flex items-center justify-center">
              <Sparkles className="w-4 h-4 text-accent-700 dark:text-accent-400" />
            </div>
            <div className="bg-gray-100 dark:bg-gray-700 rounded-xl rounded-bl-sm px-3 py-2">
              <LoadingState compact label="Pensando..." />
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      <form onSubmit={handleSubmit} className="flex gap-2 items-end">
        <textarea
          ref={textareaRef}
          rows={2}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onFocus={onExpand}
          onKeyDown={(e) => {
            // Enter envía; Shift+Enter agrega una línea.
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              handleSubmit(e);
            }
          }}
          placeholder="Escribe un filtro en lenguaje natural..."
          className="input flex-1 text-sm resize-none dark:bg-gray-700 dark:border-gray-600 dark:text-gray-100 dark:placeholder-gray-400"
          disabled={loading}
        />
        <button
          type="submit"
          aria-label="Enviar"
          disabled={!input.trim() || loading}
          className="btn-primary p-2"
        >
          <Send className="w-4 h-4" />
        </button>
      </form>
    </div>
  );
}
