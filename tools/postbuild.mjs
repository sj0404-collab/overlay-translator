import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const htmlPath = resolve(root, 'app/src/main/assets/tsx/index.html')
const html = readFileSync(htmlPath, 'utf8')

if (!html.match(/<div id="root">/)) {
  console.error('[postbuild] index.html has no #root element')
  process.exit(1)
}

let out = html

out = out.replace(
  /<script type="module" crossorigin src="\.\/assets\/([^"]+)"><\/script>/,
  (_, name) => `<script defer src="./assets/${name}"></script>`,
)

out = out.replace(
  /<link rel="stylesheet" crossorigin href="\.\/assets\/([^"]+)">/,
  (_, name) => `<link rel="stylesheet" href="./assets/${name}">`,
)

if (/type="module"|crossorigin/.test(out)) {
  console.error('[postbuild] index.html still references module scripts or crossorigin after rewrite')
  process.exit(1)
}

const assets = [...out.matchAll(/src="\.\/assets\/([^"]+)"|href="\.\/assets\/([^"]+)"/g)]
for (const m of assets) {
  const name = m[1] || m[2]
  const file = resolve(root, 'app/src/main/assets/tsx/assets', name)
  if (!existsSync(file)) {
    console.error(`[postbuild] referenced asset missing: ${name}`)
    process.exit(1)
  }
}

if (assets.length === 0) {
  console.error('[postbuild] no bundled assets referenced in index.html')
  process.exit(1)
}

const watchdog = `
<script>
(function () {
  var mounted = function () {
    return !!(document.querySelector('.shell') || document.querySelector('.overlay-shell'))
  }
  window.__tsxMounted = mounted
  setTimeout(function () {
    if (mounted()) return
    var root = document.getElementById('root')
    if (!root) return
    root.innerHTML =
      '<main style="min-height:100vh;padding:28px;box-sizing:border-box;background:#08111e;color:#edf3ff;' +
      'font-family:system-ui,sans-serif">' +
      '<p style="letter-spacing:.16em;font-size:11px;color:#8eb2ff">LOCAL SCREEN READER</p>' +
      '<h1>Не удалось загрузить интерфейс</h1>' +
      '<p style="color:#ffb4a8">Локальный интерфейс не запустился. Закройте и откройте приложение.</p></main>'
  }, 8000)
})()
</script>`

if (!out.includes('</body>')) {
  console.error('[postbuild] index.html has no </body>')
  process.exit(1)
}
out = out.replace('</body>', watchdog + '\n</body>')

writeFileSync(htmlPath, out)
console.log('[postbuild] index.html converted to classic script for file:// WebView')