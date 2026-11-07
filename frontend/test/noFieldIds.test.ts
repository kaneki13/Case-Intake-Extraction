import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const frontendDir = resolve(__dirname, '..')
const srcDir = join(frontendDir, 'src')
const catalogPath = resolve(frontendDir, '../catalog/field_catalog.json')

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}

describe('noFieldIdsInSource', () => {
  it('no catalog field id appears in src/**/*.{ts,tsx,css}', () => {
    const catalog = JSON.parse(readFileSync(catalogPath, 'utf8')) as {
      fields: { id: string }[]
    }
    const ids = catalog.fields.map((f) => f.id)
    expect(ids.length).toBeGreaterThan(0)

    // Guard against a vacuous pass: the scan must actually cover source files.
    expect(existsSync(srcDir), 'frontend/src must exist').toBe(true)
    const files = walk(srcDir).filter((f) => /\.(ts|tsx|css)$/.test(f))
    expect(files.length, 'frontend/src must contain ts/tsx/css files').toBeGreaterThan(0)

    const hits: string[] = []
    for (const file of files) {
      const text = readFileSync(file, 'utf8')
      for (const id of ids) {
        if (new RegExp(`\\b${id}\\b`).test(text)) {
          hits.push(`${file.slice(frontendDir.length + 1)}: ${id}`)
        }
      }
    }
    expect(hits, `field ids found in source:\n${hits.join('\n')}`).toEqual([])
  })
})
