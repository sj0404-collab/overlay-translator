export type NativeState = {
  overlay: boolean
  capture: boolean
  running: boolean
}

type OverlayNative = {
  state: () => string
  requestOverlay: () => void
  requestCapture: () => void
  startOverlay: () => void
  stopOverlay: () => void
}

declare global {
  interface Window {
    OverlayNative?: OverlayNative
    onOverlayNativeState?: (serialized: string) => void
  }
}

export function nativeState(): NativeState {
  try {
    return JSON.parse(window.OverlayNative?.state() ?? '{}') as NativeState
  } catch {
    return { overlay: false, capture: false, running: false }
  }
}

export function invoke(action: keyof Omit<OverlayNative, 'state'>) {
  window.OverlayNative?.[action]?.()
}
