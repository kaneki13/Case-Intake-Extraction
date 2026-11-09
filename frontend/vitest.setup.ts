import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { resetReviews } from './test/msw/handlers'
import { server } from './test/msw/server'
import { clearTestQueryClients } from './test/queryClients'

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
// jsdom has <dialog> without its methods: open and close by the attribute, as a browser would.
HTMLDialogElement.prototype.showModal ??= function showModal(this: HTMLDialogElement) {
  this.setAttribute('open', '')
}
HTMLDialogElement.prototype.close ??= function close(this: HTMLDialogElement) {
  this.removeAttribute('open')
  this.dispatchEvent(new Event('close'))
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
// globals are off, so Testing Library cannot register its own cleanup: unmount between tests.
afterEach(() => {
  cleanup()
  // Unmounting leaves the clients' queries (and any request in flight) behind: clear them before the next test.
  clearTestQueryClients()
  server.resetHandlers()
  resetReviews()
})
afterAll(() => server.close())
