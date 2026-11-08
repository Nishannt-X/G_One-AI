const LANGUAGES: Record<string, string> = {
  'hi-IN': 'Hindi',
  'en-IN': 'English',
  'bn-IN': 'Bengali',
  'ta-IN': 'Tamil',
  'te-IN': 'Telugu',
  'kn-IN': 'Kannada',
  'ml-IN': 'Malayalam',
  'mr-IN': 'Marathi',
  'gu-IN': 'Gujarati',
  'pa-IN': 'Punjabi',
}

/** "hi-IN" becomes "Hindi"; an unknown code is shown as it is. */
export function languageLabel(code: string): string {
  return LANGUAGES[code] ?? code
}

/** A length of time as m:ss, or h:mm:ss from one hour. */
export function duration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const s = String(total % 60).padStart(2, '0')
  const m = Math.floor(total / 60) % 60
  const h = Math.floor(total / 3600)
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

/** How far into the call something happened, such as "+0:12". */
export function offset(atMs: number, startMs: number): string {
  return `+${duration(atMs - startMs)}`
}

const DATE = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' })

export function shortDate(ms: number): string {
  return DATE.format(new Date(ms))
}

/** "usual_buyer_type" becomes "usual buyer type". */
export function humanize(field: string): string {
  return field.replace(/_/g, ' ')
}

export function shortId(id: string, keep = 14): string {
  return id.length > keep ? `${id.slice(0, keep)}…` : id
}
