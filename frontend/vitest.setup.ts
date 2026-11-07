import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { resetReviews } from './test/msw/handlers'
import { server } from './test/msw/server'

// Minimal shims for browser APIs jsdom lacks, so Radix Select/Popover and the text's scroll can run.
// Each is a no-op; tests that assert on scrolling still install their own spy over scrollIntoView.
// Radix Select opens on pointerdown and checks pointer capture; floating-ui measures with ResizeObserver.
class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= NoopResizeObserver
Element.prototype.scrollIntoView ??= function scrollIntoView() {}
Element.prototype.hasPointerCapture ??= function hasPointerCapture() {
  return false
}
Element.prototype.setPointerCapture ??= function setPointerCapture() {}
Element.prototype.releasePointerCapture ??= function releasePointerCapture() {}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
// globals are off, so Testing Library cannot register its own cleanup: unmount between tests.
afterEach(() => {
  cleanup()
  server.resetHandlers()
  resetReviews()
})
afterAll(() => server.close())
