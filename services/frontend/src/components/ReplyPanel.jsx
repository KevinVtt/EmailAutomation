import { useState, useEffect } from 'react';
import { api } from '../lib/api';
import { Wand2, Copy, Check, RotateCcw, Settings, X, ChevronDown } from 'lucide-react';

const TONES = [
  { id: 'formal', label: 'Formal', desc: 'Profesional y respetuoso' },
  { id: 'casual', label: 'Casual', desc: 'Relajado e informal' },
  { id: 'amigable', label: 'Amigable', desc: 'Cálido y cercano' },
  { id: 'directo', label: 'Directo', desc: 'Conciso, al punto' },
];

const LANGUAGES = [
  { id: 'auto', label: 'Automático' },
  { id: 'es', label: 'Español' },
  { id: 'en', label: 'English' },
  { id: 'pt', label: 'Português' },
  { id: 'fr', label: 'Français' },
];

function getStoredSettings() {
  try {
    const stored = localStorage.getItem('rewriteSettings');
    if (stored) return JSON.parse(stored);
  } catch {}
  return { tone: 'formal', language: 'auto', customRules: '' };
}

function storeSettings(settings) {
  localStorage.setItem('rewriteSettings', JSON.stringify(settings));
}

export default function ReplyPanel({ email, onClose }) {
  const [draft, setDraft] = useState('');
  const [rewritten, setRewritten] = useState('');
  const [loading, setLoading] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(null);
  const [showSettings, setShowSettings] = useState(false);
  const [settings, setSettings] = useState(getStoredSettings);

  useEffect(() => {
    storeSettings(settings);
  }, [settings]);

  const handleImprove = async () => {
    if (!draft.trim() || loading) return;
    setLoading(true);
    setError(null);
    setRewritten('');

    try {
      const result = await api.rewrite.improve(draft, {
        originalSubject: email?.subject || '',
        originalFrom: email?.fromAddress || '',
        originalBody: email?.bodyPreview || '',
        tone: settings.tone,
        language: settings.language,
        customRules: settings.customRules,
      });
      setRewritten(result.rewritten || '');
    } catch (err) {
      console.error('Rewrite failed:', err);
      setError('Error al mejorar el borrador. Intentá de nuevo.');
    } finally {
      setLoading(false);
    }
  };

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(rewritten);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Fallback
      const ta = document.createElement('textarea');
      ta.value = rewritten;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleReset = () => {
    setDraft('');
    setRewritten('');
    setError(null);
  };

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between mb-3 pb-3 border-b border-gray-200 dark:border-gray-700">
        <h3 className="text-sm font-semibold text-gray-900 dark:text-gray-100">
          Redactar respuesta
        </h3>
        <div className="flex items-center gap-1">
          <button
            onClick={() => setShowSettings(!showSettings)}
            className={`p-1.5 rounded-lg transition-colors ${
              showSettings
                ? 'text-primary-600 bg-primary-50 dark:bg-primary-900/30'
                : 'text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700'
            }`}
            title="Configuración de redacción"
          >
            <Settings className="w-4 h-4" />
          </button>
          <button
            onClick={onClose}
            className="p-1.5 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Settings panel */}
      {showSettings && (
        <div className="mb-3 p-3 bg-gray-50 dark:bg-gray-700/50 rounded-lg space-y-3">
          <div>
            <label className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-1 block">Tono</label>
            <div className="flex flex-wrap gap-1.5">
              {TONES.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setSettings(s => ({ ...s, tone: t.id }))}
                  className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                    settings.tone === t.id
                      ? 'bg-primary-600 text-white'
                      : 'bg-gray-200 dark:bg-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-500'
                  }`}
                  title={t.desc}
                >
                  {t.label}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-1 block">Idioma de respuesta</label>
            <div className="flex flex-wrap gap-1.5">
              {LANGUAGES.map((l) => (
                <button
                  key={l.id}
                  onClick={() => setSettings(s => ({ ...s, language: l.id }))}
                  className={`text-xs px-2.5 py-1 rounded-full transition-colors ${
                    settings.language === l.id
                      ? 'bg-primary-600 text-white'
                      : 'bg-gray-200 dark:bg-gray-600 text-gray-700 dark:text-gray-300 hover:bg-gray-300 dark:hover:bg-gray-500'
                  }`}
                >
                  {l.label}
                </button>
              ))}
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-1 block">
              Reglas personalizadas
            </label>
            <textarea
              value={settings.customRules}
              onChange={(e) => setSettings(s => ({ ...s, customRules: e.target.value }))}
              placeholder="Ej: Siempre incluir saludo formal. No usar abreviaciones. Mencionar número de ticket si existe."
              className="w-full text-xs border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 rounded-lg px-2 py-1.5 resize-none focus:outline-none focus:border-primary-400 placeholder-gray-400 dark:placeholder-gray-500"
              rows={3}
            />
          </div>
        </div>
      )}

      {/* Draft textarea */}
      <div className="mb-3">
        <label className="text-xs font-medium text-gray-600 dark:text-gray-400 mb-1 block">
          Tu borrador
        </label>
        <textarea
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="Escribí tu respuesta acá... la IA la va a mejorar."
          className="w-full text-sm border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 rounded-lg px-3 py-2 resize-none focus:outline-none focus:border-primary-400 placeholder-gray-400 dark:placeholder-gray-500"
          rows={5}
        />
      </div>

      {/* Action buttons */}
      <div className="flex gap-2 mb-3">
        <button
          onClick={handleImprove}
          disabled={!draft.trim() || loading}
          className="btn-primary flex items-center gap-1.5 text-sm"
        >
          {loading ? (
            <>
              <RotateCcw className="w-4 h-4 animate-spin" />
              Mejorando...
            </>
          ) : (
            <>
              <Wand2 className="w-4 h-4" />
              Mejorar con IA
            </>
          )}
        </button>
        {draft && (
          <button
            onClick={handleReset}
            className="px-3 py-2 text-sm text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 rounded-lg transition-colors"
          >
            Limpiar
          </button>
        )}
      </div>

      {/* Error */}
      {error && (
        <div className="mb-3 text-xs text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">
          {error}
        </div>
      )}

      {/* Rewritten output */}
      {rewritten && (
        <div className="flex-1 overflow-y-auto">
          <div className="flex items-center justify-between mb-1">
            <label className="text-xs font-medium text-green-700 dark:text-green-400">
              Texto mejorado
            </label>
            <button
              onClick={handleCopy}
              className="flex items-center gap-1 text-xs px-2 py-1 rounded-md bg-green-50 dark:bg-green-900/30 text-green-700 dark:text-green-400 hover:bg-green-100 dark:hover:bg-green-900/50 transition-colors"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5" />
                  Copiado
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  Copiar
                </>
              )}
            </button>
          </div>
          <div className="text-sm text-gray-800 dark:text-gray-200 bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800 rounded-lg px-3 py-2 whitespace-pre-wrap leading-relaxed">
            {rewritten}
          </div>
        </div>
      )}
    </div>
  );
}
