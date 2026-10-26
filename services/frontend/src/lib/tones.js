// Debe coincidir con los tonos válidos de services/ai-python (rewrite_agent.valid_tones).
export const TONES = [
  { id: 'profesional_amistoso', label: 'Profesional y amistoso', desc: 'Cordial y cálido sin perder formalidad' },
  { id: 'formal', label: 'Formal', desc: 'Profesional y respetuoso' },
  { id: 'amigable', label: 'Amigable', desc: 'Cálido y cercano' },
  { id: 'directo', label: 'Directo', desc: 'Conciso, al punto' },
  { id: 'empatico', label: 'Empático', desc: 'Reconoce la situación del otro' },
  { id: 'persuasivo', label: 'Persuasivo', desc: 'Busca una respuesta o acción' },
  { id: 'entusiasta', label: 'Entusiasta', desc: 'Positivo y con energía' },
  { id: 'casual', label: 'Casual', desc: 'Relajado e informal' },
];

export const DEFAULT_TONE = 'profesional_amistoso';
