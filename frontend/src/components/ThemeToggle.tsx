import { useState } from 'react'
import { currentTheme, setTheme, type Theme } from '../lib/theme'
import { Icon } from './Icon'
import { Tip } from './Tip'
import styles from './ThemeToggle.module.css'

/** Light/dark switch: sets data-theme on <html> and remembers the choice. */
export function ThemeToggle() {
  const [theme, setThemeState] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'
  const label = `Switch to ${next} theme`
  return (
    <Tip label={label}>
      <button
        type="button"
        className={styles.toggle}
        aria-label={label}
        onClick={() => {
          setTheme(next)
          setThemeState(next)
        }}
      >
        <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={16} />
      </button>
    </Tip>
  )
}
