export type NativeState = {
  overlay: boolean
  capture: boolean
  running: boolean
}

export type OverlayState = {
  frame: boolean
  scanning: boolean
  tts: boolean
  text: string
}

type OverlayNative = {
  state: () => string
  requestOverlay: () => void
  requestCapture: () => void
  startOverlay: () => void
  stopOverlay: () => void
  pickFrame: () => void
  scanFrame: () => void
  speak: () => void
  copy: () => void
}

declare global {
  interface Window {
    OverlayNative?: OverlayNative
    onOverlayNativeState?: (serialized: string) => void
    onOverlayOcrResult?: (text: string) => void
  }
}

export function nativeState(): NativeState {
  try {
    return JSON.parse(window.OverlayNative?.state() ?? '{}') as NativeState
  } catch {
    return { overlay: false, capture: false, running: false }
  }
}

export function overlayState(): OverlayState {
  try {
    return JSON.parse(window.OverlayNative?.state() ?? '{}') as OverlayState
  } catch {
    return { frame: false, scanning: false, tts: false, text: '' }
  }
}

export function invoke(action: keyof Omit<OverlayNative, 'state'>) {
  window.OverlayNative?.[action]?.()
}
