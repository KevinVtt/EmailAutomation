import { useState, useRef, useEffect } from 'react';
import { api } from '../lib/api';
import { Sparkles, Send, Bot, User } from 'lucide-react';

export default function ChatPanel({ onFiltersApplied, chatResponse, onChatConsumed }) {
  const [messages, setMessages] = useState([
    {
      role: 'assistant',
      content: '¡Hola! Puedes pedirme que filtre tus emails. Por ejemplo:\n\n• "Muéstrame los emails importantes de esta semana"\n• "Busca emails de facturación"\n• "¿Qué emails tengo sin leer de ayer?"',
    },
  ]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [conversationId, setConversationId] = useState('');
  const messagesEndRef = useRef(null);
  const processedCids = useRef(new Set());

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (!chatResponse) return;
    if (chatResponse.conversation_id && processedCids.current.has(chatResponse.conversation_id)) {
      return;
    }

    if (chatResponse.conversation_id) processedCids.current.add(chatResponse.conversation_id);
    setMessages((prev) => [
      ...prev,
      { role: 'assistant', content: chatResponse.response },
    ]);
    setLoading(false);
    onChatConsumed?.();

    if (chatResponse.criteria && Object.keys(chatResponse.criteria).length > 0) {
      onFiltersApplied?.(chatResponse.criteria);
    }
  }, [chatResponse, onFiltersApplied, onChatConsumed]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!input.trim() || loading) {
      console.log('[ChatPanel] handleSubmit bloqueado - input vacío o loading');
      return;
    }

    const userMessage = input.trim();
    setInput('');
    setMessages((prev) => [...prev, { role: 'user', content: userMessage }]);
    setLoading(true);

    try {
      const response = await api.chat.send(userMessage, conversationId);
      const cid = response.conversationId || response.conversation_id || conversationId;
      setConversationId(cid);
      if (cid) processedCids.current.add(cid);
      if (response.response) {
        setMessages((prev) => [
          ...prev,
          { role: 'assistant', content: response.response },
        ]);
        setLoading(false);
      }
      if (response.criteria && Object.keys(response.criteria).length > 0) {
        onFiltersApplied?.(response.criteria);
      }
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: 'Lo siento, hubo un error al procesar tu mensaje.' },
      ]);
      setLoading(false);
    }
  };

  return (
    <div className="card flex flex-col h-full">
      <div className="flex items-center gap-2 mb-4 pb-3 border-b border-gray-200">
        <Bot className="w-5 h-5 text-primary-600" />
        <h2 className="text-lg font-semibold text-gray-900">Asistente IA</h2>
      </div>

      <div className="flex-1 overflow-y-auto space-y-3 mb-4 -mx-4 px-4">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`flex gap-2 ${msg.role === 'user' ? 'justify-end' : ''}`}
          >
            {msg.role === 'assistant' && (
              <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center flex-shrink-0">
                <Sparkles className="w-4 h-4 text-primary-600" />
              </div>
            )}
            <div
              className={`max-w-[85%] rounded-xl px-3 py-2 text-sm ${
                msg.role === 'user'
                  ? 'bg-primary-600 text-white rounded-br-sm'
                  : 'bg-gray-100 text-gray-800 rounded-bl-sm'
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
            <div className="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
              <Sparkles className="w-4 h-4 text-primary-600" />
            </div>
            <div className="bg-gray-100 rounded-xl rounded-bl-sm px-3 py-2">
              <div className="flex gap-1">
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce" />
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce delay-100" />
                <div className="w-2 h-2 bg-gray-400 rounded-full animate-bounce delay-200" />
              </div>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      <form onSubmit={handleSubmit} className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="Escribe un filtro en lenguaje natural..."
          className="input flex-1 text-sm"
          disabled={loading}
        />
        <button
          type="submit"
          disabled={!input.trim() || loading}
          className="btn-primary p-2"
        >
          <Send className="w-4 h-4" />
        </button>
      </form>
    </div>
  );
}
