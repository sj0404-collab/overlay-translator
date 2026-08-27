import { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { invoke, nativeState, type NativeState } from './native'
import './styles.css'

type StepProps = {
  number: string
  title: string
  body: string
  ready: boolean
  action: string
  onAction: () => void
}

function PermissionStep({ number, title, body, ready, action, onAction }: StepProps) {
  return (
    <article className={ready ? 'step is-ready' : 'step'}>
      <div className="step-number">{ready ? '✓' : number}</div>
      <div className="step-copy">
        <h2>{title}</h2>
        <p>{body}</p>
      </div>
      <button className="outline-action" onClick={onAction}>{ready ? 'Разрешено' : action}</button>
    </article>
  )
}

function App() {
  const [state, setState] = useState<NativeState>(nativeState)
  const ready = state.overlay && state.capture

  useEffect(() => {
    window.onOverlayNativeState = (serialized) => {
      try { setState(JSON.parse(serialized) as NativeState) } catch { setState(nativeState()) }
    }
    return () => { window.onOverlayNativeState = undefined }
  }, [])

  return (
    <main className="shell">
      <header className="brand-row">
        <div className="mark" aria-hidden="true"><i /><i /><i /></div>
        <div>
          <p className="eyebrow">LOCAL SCREEN READER</p>
          <h1>Рамка OCR</h1>
        </div>
        <span className="local-pill">Локально</span>
      </header>

      <section className="hero">
        <p className="hero-label">Только выбранная область</p>
        <h2>Считывайте страницу, <em>а не весь экран.</em></h2>
        <p>После запуска оверлея обведите рамкой саму страницу или конкретное облачко. Элементы системы и панель управления вне рамки не участвуют в распознавании.</p>
      </section>

      <section className="steps" aria-label="Разрешения">
        <PermissionStep
          number="01"
          title="Разрешить оверлей"
          body="Нужно, чтобы показать рамку и карточку результата поверх читалки."
          ready={state.overlay}
          action="Открыть настройки"
          onAction={() => invoke('requestOverlay')}
        />
        <PermissionStep
          number="02"
          title="Разрешить захват экрана"
          body="Android покажет системный запрос. Кадр обрабатывается локально только после выбора рамки."
          ready={state.capture}
          action="Разрешить"
          onAction={() => invoke('requestCapture')}
        />
      </section>

      <section className="privacy-card">
        <span className="lock">⌁</span>
        <p><strong>Без облака.</strong> Встроенный Cyrillic PP‑OCR и системный русский голос работают на устройстве. Снимок страницы не отправляется на сервер.</p>
      </section>

      <button
        className="start"
        disabled={!ready}
        onClick={() => invoke(state.running ? 'stopOverlay' : 'startOverlay')}
      >
        <span>{state.running ? 'Остановить оверлей' : 'Запустить оверлей'}</span>
        <b>{state.running ? '■' : '↗'}</b>
      </button>

      <footer>
        <span className={ready ? 'status-dot ready' : 'status-dot'} />
        {ready ? 'Готово: запустите поверх страницы и нажмите «Рамка».' : 'Сначала выдайте два разрешения.'}
      </footer>
    </main>
  )
}

createRoot(document.getElementById('root')!).render(<App />)
