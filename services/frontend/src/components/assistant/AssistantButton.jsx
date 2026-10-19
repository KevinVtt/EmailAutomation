import { Sparkles } from 'lucide-react';

export default function AssistantButton({ open, onToggle }) {
  return (
    <button
      type="button"
      aria-label="Asistente"
      aria-expanded={open}
      onClick={onToggle}
      className="fixed bottom-6 right-6 z-40 w-14 h-14 rounded-full bg-primary-700 text-white shadow-lg hover:bg-primary-800 focus:outline-none focus:ring-2 focus:ring-primary-400 transition-colors"
    >
      <Sparkles className="w-6 h-6 mx-auto" />
    </button>
  );
}
