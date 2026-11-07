import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const srcDir = resolve(__dirname, '../src')
const read = (rel: string) => readFileSync(join(srcDir, rel), 'utf8')
function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}
const cssFiles = () => walk(srcDir).filter((f) => f.endsWith('.css'))
const rel = (f: string) => f.slice(srcDir.length + 1)

/** The custom properties declared in the first block whose selector line matches `selector`. */
function tokensOf(css: string, selector: RegExp): Record<string, string> {
  const at = css.search(selector)
  if (at < 0) throw new Error(`no block ${selector}`)
  const open = css.indexOf('{', at)
  const close = css.indexOf('}', open)
  const out: Record<string, string> = {}
  for (const m of css.slice(open + 1, close).matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) out[m[1] as string] = (m[2] as string).trim()
  return out
}

const rgb = (hex: string): number[] => {
  if (!/^#[0-9a-f]{6}$/i.test(hex)) throw new Error(`not a #rrggbb colour: ${hex}`)
  const n = parseInt(hex.slice(1), 16)
  return [n >> 16, (n >> 8) & 255, n & 255]
}
const luminance = (v: number[]) =>
  v
    .map((c) => {
      const s = c / 255
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
    })
    .reduce((a, c, i) => a + c * ([0.2126, 0.7152, 0.0722][i] as number), 0)
const contrast = (a: number[], b: number[]) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (hi + 0.05) / (lo + 0.05)
}
/** `color-mix(in srgb, tone p, transparent)` drawn over `base`. */
const over = (tone: number[], base: number[], p: number) => tone.map((c, i) => c * p + (base[i] as number) * (1 - p))

const tokens = read('styles/tokens.css')
const themes = {
  light: tokensOf(tokens, /:root\[data-theme='light'\]/),
  dark: tokensOf(tokens, /:root\[data-theme='dark'\]/),
}
const STATUS = ['--ok', '--caution', '--risk', '--conflict', '--edited', '--muted'] as const

describe('polish', () => {
  it('statusTokensMeetWcagAaInBothThemes', () => {
    const failures: string[] = []
    for (const [name, t] of Object.entries(themes)) {
      const surface = rgb(t['--surface'] as string)
      const selectedRow = rgb(t['--accent-tint'] as string)
      for (const token of STATUS) {
        const tone = rgb(t[token] as string)
        const onSurface = contrast(tone, surface)
        // A status badge is the tone at 10% over its row; the selected row is the accent tint.
        const onSelectedBadge = contrast(tone, over(tone, selectedRow, 0.1))
        if (onSurface < 4.5) failures.push(`${name} ${token} on --surface: ${onSurface.toFixed(2)}`)
        if (onSelectedBadge < 4.5) failures.push(`${name} ${token} badge on a selected row: ${onSelectedBadge.toFixed(2)}`)
      }
    }
    expect(failures).toEqual([])
  })

  it('systemDarkThemeMatchesTheExplicitDarkTheme', () => {
    expect(tokensOf(tokens, /@media \(prefers-color-scheme: dark\)/)).toEqual(themes.dark)
    const root = tokensOf(tokens, /^:root \{/m)
    for (const [k, v] of Object.entries(themes.light)) expect(root[k], k).toBe(v)
  })

  it('noTextBelow13px', () => {
    const hits: string[] = []
    for (const file of cssFiles()) {
      for (const m of readFileSync(file, 'utf8').matchAll(/font-size:\s*([^;]+);/g)) {
        const value = (m[1] as string).trim()
        const px = /^(\d+(?:\.\d+)?)px$/.exec(value)
        if (value === 'var(--t12)' || (px && Number(px[1]) < 13)) hits.push(`${rel(file)}: ${value}`)
      }
    }
    expect(hits).toEqual([])
  })

  it('motionIsDisabledUnderReducedMotion', () => {
    // Durations are tokens that drop to 0 under reduced motion; a literal duration needs its own opt-out.
    expect(tokens).toMatch(/@media \(prefers-reduced-motion: reduce\)\s*\{\s*:root\s*\{[^}]*--dur-fast:\s*0ms;[^}]*--dur:\s*0ms;/)
    const hits: string[] = []
    for (const file of cssFiles()) {
      const css = readFileSync(file, 'utf8')
      const literal = /(?:animation|transition)[^;]*\b\d*\.?\d+m?s\b/.test(css)
      if (literal && !/prefers-reduced-motion: reduce/.test(css)) hits.push(rel(file))
      // Every keyframe animation is switched off under reduced motion in its own file.
      if (/@keyframes/.test(css) && !/prefers-reduced-motion: reduce[\s\S]*animation:\s*none/.test(css)) {
        hits.push(`${rel(file)} (keyframes)`)
      }
    }
    expect(hits).toEqual([])
  })

  it('focusRingIsNeverRemovedFromFocusVisible', () => {
    expect(read('styles/base.css')).toMatch(/:focus-visible\s*\{\s*outline:\s*2px solid var\(--focus\)/)
    const hits: string[] = []
    for (const file of cssFiles()) {
      for (const m of readFileSync(file, 'utf8').matchAll(/([^{}]*):focus-visible[^{]*\{([^}]*)\}/g)) {
        if (/outline:\s*(none|0)\b/.test(m[2] as string)) hits.push(`${rel(file)}: ${(m[1] as string).trim()}`)
      }
    }
    expect(hits).toEqual([])
  })

  it('scrollContainersContainAbsoluteDescendants', () => {
    // Visually-hidden text is absolutely positioned. In a scroller that is not a containing block it
    // overflows the page itself, and scrollIntoView then scrolls the whole app.
    const hits: string[] = []
    for (const file of cssFiles()) {
      for (const m of readFileSync(file, 'utf8').matchAll(/([^{}]+)\{([^}]*)\}/g)) {
        const body = m[2] as string
        if (/overflow-y:\s*auto/.test(body) && !/position:\s*(relative|absolute|fixed|sticky)/.test(body)) {
          hits.push(`${rel(file)}: ${(m[1] as string).trim()}`)
        }
      }
    }
    expect(hits).toEqual([])
  })

  it('selectedQuoteAnnouncementIsNotCopiedWithTheText', () => {
    // The visually-hidden "selected quote" text sits inside the document text: a copy must not pick it up.
    expect(read('components/DocumentView.module.css')).toMatch(/\.text :global\(\.visually-hidden\)\s*\{[^}]*user-select:\s*none/)
  })

  it('placeholderWordingIsDefinedOnce', () => {
    const sources = walk(srcDir).filter((f) => /\.tsx?$/.test(f))
    for (const words of ['None stated', 'Not in this output']) {
      // A string literal, or JSX text standing alone between tags.
      const literal = new RegExp(`['"\`>]${words}['"\`<]`, 'g')
      const hits = sources.flatMap((f) => [...readFileSync(f, 'utf8').matchAll(literal)].map(() => rel(f)))
      expect(hits, words).toHaveLength(1)
    }
  })
})
