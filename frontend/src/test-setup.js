import { vi } from 'vitest'

// jsdom 没有布局引擎，为组件的尺寸监听提供测试替身。
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
if (typeof window !== 'undefined') {
  window.matchMedia = vi.fn().mockImplementation(() => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
  }))
}
