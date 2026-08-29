export type NativeState = {
  overlay: boolean
  capture: boolean
  running: boolean
}

export type VoiceOption = {
  name: string
  label: string
  selected: boolean
}

export type OverlayState = {
  frame: boolean
  scanning: boolean
  tts: boolean
  text: string
  voices: VoiceOption[]
  selectedVoice: string
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
  listVoices: () => string
  selectVoice: (name: string) => void
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
  const fallback: OverlayState = { frame: false, scanning: false, tts: false, text: '', voices: [], selectedVoice: '' }
  try {
    const parsed = JSON.parse(window.OverlayNative?.state() ?? '{}') as Partial<OverlayState>
    return {
      ...fallback,
      ...parsed,
      voices: Array.isArray(parsed.voices) ? parsed.voices : [],
      selectedVoice: parsed.selectedVoice ?? '',
    }
  } catch {
    return fallback
  }
}

export function invoke(action: keyof Omit<OverlayNative, 'state'>, value?: string) {
  const fn = window.OverlayNative?.[action]
  if (!fn) return
  if (value === undefined) (fn as () => void)()
  else (fn as (value: string) => void)(value)
}
