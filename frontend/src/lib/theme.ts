export type Theme = 'light' | 'dark'

const KEY = 'theme'

/** The reviewer's saved choice, or null when none is saved or storage is unavailable. */
export function storedTheme(): Theme | null {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : null
  } catch {
    return null // storage blocked (private mode, sandbox): fall back to the system theme
  }
}

function systemTheme(): Theme {
  if (typeof window.matchMedia !== 'function') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

/** The theme currently on screen: the explicit data-theme, else the system preference. */
export function currentTheme(): Theme {
  const set = document.documentElement.dataset.theme
  return set === 'light' || set === 'dark' ? set : systemTheme()
}

/** Apply a theme to <html> and remember it. Storage failures only lose persistence. */
export function setTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    // storage blocked: the theme still applies for this session
  }
}

/** Run once before the first render so a saved theme never flashes the other one. */
export function applyStoredTheme(): void {
  const saved = storedTheme()
  if (saved) document.documentElement.dataset.theme = saved
}
