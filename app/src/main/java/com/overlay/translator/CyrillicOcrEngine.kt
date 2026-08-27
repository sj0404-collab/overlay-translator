package com.overlay.translator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Downloadable Russian/Cyrillic OCR based on PaddleOCR mobile TFLite models.
 *
 * The detector finds text-line blobs on a full page. PP-OCRv3 recognizes each
 * crop; PP-OCRv5 is evaluated for every Cyrillic crop and ranked against v3.
 * No dictionary spell replacement is performed: benchmarks showed that
 * it could turn a visually correct word (for example "мятой") into a wrong but
 * frequent dictionary word. Models are bundled with this standalone overlay
 * and copied to app-private storage before LiteRT opens them.
 */
internal class CyrillicOcrEngine(
    private val context: Context,
    private val environment: Environment = Environment.create(),
) : OcrEngine {

    private lateinit var detector: CompiledModel
    private lateinit var primary: CompiledModel
    private var verifier: CompiledModel? = null

    private lateinit var detectorInput: TensorBuffer
    private lateinit var detectorOutput: TensorBuffer
    private lateinit var primaryInput: TensorBuffer
    private lateinit var primaryOutput: TensorBuffer
    private var verifierInput: TensorBuffer? = null
    private var verifierOutput: TensorBuffer? = null

    private lateinit var primaryChars: List<String>
    private var verifierChars: List<String>? = null

    private val detectorPixels = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
    private val detectorFloats = FloatArray(DETECTOR_SIZE * DETECTOR_SIZE * 3)
    private val recognizerPixels = IntArray(RECOGNIZER_HEIGHT * RECOGNIZER_WIDTH)
    private val recognizerFloats = FloatArray(RECOGNIZER_HEIGHT * RECOGNIZER_WIDTH * 3)
    private val componentQueue = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
    private val visited = BooleanArray(DETECTOR_SIZE * DETECTOR_SIZE)

    private lateinit var detectorBitmap: Bitmap
    private lateinit var detectorCanvas: Canvas
    private lateinit var detectorPaint: Paint
    private lateinit var recognizerBitmap: Bitmap
    private lateinit var recognizerCanvas: Canvas
    private lateinit var recognizerPaint: Paint

    private val lock = Any()
    private val textPostprocessor = TextPostprocessor()

    @Volatile
    private var initialized = false

    private data class TextBox(val rect: Rect) {
        val centerY: Float get() = (rect.top + rect.bottom) / 2f
        val height: Int get() = rect.height()
    }

    private enum class RecognitionModel { V3, V5 }

    private data class Recognition(
        val text: String,
        val confidence: Float,
        val model: RecognitionModel = RecognitionModel.V3,
    )

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        if (!init()) throw IllegalStateException("Локальные модели Cyrillic OCR недоступны")
    }

    private fun init(): Boolean {
        val detectorPath = BundledCyrillicModels.resolve(context, DETECTOR_PATH) ?: return missing(DETECTOR_PATH)
        val primaryPath = BundledCyrillicModels.resolve(context, PRIMARY_PATH) ?: return missing(PRIMARY_PATH)
        val primaryDict = BundledCyrillicModels.resolve(context, PRIMARY_DICT_PATH) ?: return missing(PRIMARY_DICT_PATH)
        val verifierPath = BundledCyrillicModels.resolve(context, VERIFIER_PATH)
        val verifierDict = BundledCyrillicModels.resolve(context, VERIFIER_DICT_PATH)

        return runCatching {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(threads, null, null)
            }
            detector = CompiledModel.create(detectorPath, options, environment)
            primary = CompiledModel.create(primaryPath, options, environment)
            verifier = if (verifierPath != null && verifierDict != null) {
                CompiledModel.create(verifierPath, options, environment)
            } else {
                null
            }

            detectorInput = detector.createInputBuffers()[0]
            detectorOutput = detector.createOutputBuffers()[0]
            primaryInput = primary.createInputBuffers()[0]
            primaryOutput = primary.createOutputBuffers()[0]
            verifierInput = verifier?.createInputBuffers()?.get(0)
            verifierOutput = verifier?.createOutputBuffers()?.get(0)

            primaryChars = readDictionary(primaryDict)
            verifierChars = verifierDict?.let(::readDictionary)

            detectorBitmap = Bitmap.createBitmap(DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888)
            detectorCanvas = Canvas(detectorBitmap)
            detectorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            recognizerBitmap = Bitmap.createBitmap(RECOGNIZER_WIDTH, RECOGNIZER_HEIGHT, Bitmap.Config.ARGB_8888)
            recognizerCanvas = Canvas(recognizerBitmap)
            recognizerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            initialized = true
            Log.i(TAG, "Cyrillic OCR initialized (PP-OCRv3 + ${if (verifier != null) "PP-OCRv5 verifier" else "no verifier"})")
            true
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize local Cyrillic OCR", error)
            closeInternal()
        }.getOrDefault(false)
    }

    private fun missing(path: String): Boolean {
        Log.w(TAG, "Bundled Cyrillic OCR model is not available: $path")
        return false
    }

    private fun readDictionary(path: String): List<String> {
        val raw = java.io.File(path).readBytes()
        // Strip UTF-8 BOM (EF BB BF) if present — GitHub raw files
        // and some editors prepend it, causing the first dict entry
        // to be corrupted (e.g. "\uFEFFА" instead of "А").
        val text = if (raw.size >= 3 &&
            raw[0] == 0xEF.toByte() &&
            raw[1] == 0xBB.toByte() &&
            raw[2] == 0xBF.toByte()
        ) {
            String(raw, 3, raw.size - 3, Charsets.UTF_8)
        } else {
            decodeDictionaryBytes(raw)
        }
        return text.lines().dropLastWhile(String::isEmpty)
    }

    /**
     * Декодирует байты словаря, определяя кодировку по содержимому.
     *
     * `String(bytes, UTF_8)` НЕ бросает исключение на невалидных байтах —
     * он молча подставляет U+FFFD. Поэтому прежний `try/catch` с откатом на
     * windows-1251 не срабатывал никогда: словарь в 1251 превращался в
     * строку из «ромбиков», CTC-индексы указывали на мусор, и вместо
     * русского текста пользователь получал крякозябры.
     *
     * Здесь UTF-8 декодируется строго (CharsetDecoder с REPORT), и только
     * при реальной ошибке используется windows-1251.
     */
    private fun decodeDictionaryBytes(raw: ByteArray): String {
        val strictUtf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        runCatching {
            return strictUtf8.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        }
        Log.i(TAG, "Dictionary is not valid UTF-8, decoding as windows-1251")
        return String(raw, java.nio.charset.Charset.forName("windows-1251"))
    }

    override fun recognizeText(image: Bitmap): String {
        ensureInitialized()
        return synchronized(lock) {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            val boxes = detectTextBoxes(image)
            if (boxes.isEmpty()) return@synchronized ""

            val recognized = boxes.mapNotNull { box ->
                val padded = pad(box.rect, image.width, image.height)
                if (padded.width() < 4 || padded.height() < 4) return@mapNotNull null
                val crop = Bitmap.createBitmap(image, padded.left, padded.top, padded.width(), padded.height())
                try {
                    val result = recognizeLineBitmap(crop)
                    // Отбрасываем только совсем безнадёжное. Раньше нижней
                    // границей де-факто служил PRIMARY_CONFIDENCE (0.90), и
                    // верно прочитанные, но «неуверенные» слова молча
                    // выпадали из текста.
                    result.text
                        .takeIf { it.isNotBlank() && result.confidence >= MIN_ACCEPT_CONFIDENCE }
                        ?.let { box to it }
                } finally {
                    crop.recycle()
                }
            }
            if (recognized.isEmpty()) return@synchronized ""

            val rows = mutableListOf<MutableList<Pair<TextBox, String>>>()
            recognized.forEach { item ->
                val row = rows.firstOrNull { existing ->
                    val center = existing.map { it.first.centerY }.average().toFloat()
                    val height = existing.map { it.first.height }.average().toFloat()
                    abs(item.first.centerY - center) <= max(item.first.height.toFloat(), height) * 0.60f
                }
                if (row != null) row += item else rows += mutableListOf(item)
            }
            rows.sortBy { row -> row.minOf { it.first.rect.top } }
            val text = rows.joinToString("\n") { row ->
                row.sortedBy { it.first.rect.left }.joinToString(" ") { it.second.trim() }
            }
            cleanRecognition(textPostprocessor.postprocess(text))
        }
    }

    /**
     * Распознаёт вырезанную детектором строку: сначала режет её на слова по
     * вертикальной проекции «чернил» (как _split_horizontal_words в Python-
     * пайплайне репозитория моделей), потом распознаёт каждое слово отдельно.
     *
     * Детектор PP-OCR склеивает короткую строку в один бокс, а распознаватель
     * обучен на отдельных словах и на целой строке не выдаёт пробелов:
     * «И ПАЛ ПОД ЛЕЗВИЕМ» выходило как «ИПАЛПОДЛЕЗВИЕМ».
     */
    private fun recognizeLineBitmap(crop: Bitmap): Recognition {
        val words = splitWords(crop)
        // PP-OCRv5 надёжно читает многие компактные строковые подписи целиком,
        // но в CTC-выходе не ставит пробелы. Не заменяем целую строку только
        // нарезанными словами: на тонких буквах нарезка может обрезать край
        // глифа и вернуть пустой результат, хотя полный кроп читается верно.
        val wholeLine = recognizeCrop(crop)
        if (words.size == 1) return wholeLine
        val sb = StringBuilder()
        var confSum = 0f
        var recognizedCount = 0
        for (piece in words) {
            try {
                val r = recognizeCrop(piece)
                if (r.text.isNotBlank()) {
                    confSum += r.confidence
                    recognizedCount++
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(r.text)
                }
            } finally {
                piece.recycle()
            }
        }
        val segmented = Recognition(
            text = sb.toString().trim(),
            confidence = if (recognizedCount == 0) 0f else confSum / recognizedCount,
            model = wholeLine.model,
        )
        return selectLineRecognition(wholeLine, segmented)
    }

    /**
     * Выбирает между полной строкой и визуально разрезанными словами.
     * Полный вариант допускается только если консервативная постобработка уже
     * способна восстановить в нём реальные границы слов. Это не словарная
     * подмена: неизвестные слитные последовательности не получают бонуса и
     * остаются на пути с визуальными промежутками.
     */
    private fun selectLineRecognition(wholeLine: Recognition, segmented: Recognition): Recognition {
        if (segmented.text.isBlank()) return wholeLine
        if (wholeLine.text.isBlank()) return segmented

        val restoredWhole = OcrTextCleaner.normalizeLocalCyrillicCaption(wholeLine.text)
        val restoresBoundaries = restoredWhole.count(Char::isWhitespace) > wholeLine.text.count(Char::isWhitespace)
        val wholeQuality = candidateQuality(wholeLine) + if (restoresBoundaries) WHOLE_LINE_BOUNDARY_BONUS else 0f
        val segmentedQuality = candidateQuality(segmented)
        return if (restoresBoundaries && wholeQuality >= segmentedQuality) wholeLine else segmented
    }

    /**
     * Вертикальная проекция чернил + порог Оцу: широкие пробелы между словами
     * разделяют кроп. Если явной щели нет — кроп остаётся целым.
     */
    private fun splitWords(crop: Bitmap): List<Bitmap> {
        if (crop.width < 32) return listOf(crop)
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        val hist = IntArray(256)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
            gray[i] = lum
            hist[lum]++
        }
        // Порог Оцу по гистограмме яркости.
        var sumAll = 0L
        for (v in 0..255) sumAll += v * hist[v]
        val total = (w * h).toLong()
        var sumB = 0L
        var wB = 0L
        var maxBetween = -1.0
        var otsu = 127
        for (v in 0..255) {
            wB += hist[v]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += v * hist[v]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > maxBetween) {
                maxBetween = between
                otsu = v
            }
        }
        fun inkColumns(lightInk: Boolean): IntArray {
            // Balloon borders are often long solid black/white rows. Counting
            // their pixels as ink marks every column as occupied and prevents
            // spaces from being split. Exclude only near-full rows; glyph rows
            // retain their normal sparse stroke pattern.
            val usableRows = BooleanArray(h)
            var usableRowCount = 0
            for (y in 0 until h) {
                var active = 0
                for (x in 0 until w) {
                    val g = gray[y * w + x]
                    if (if (lightInk) g > otsu else g < otsu) active++
                }
                if (active in 1 until (w * 0.72f).toInt().coerceAtLeast(1)) {
                    usableRows[y] = true
                    usableRowCount++
                }
            }
            // A crop can be a very bold word whose strokes occupy most rows.
            // In that case retain the central band instead of discarding all
            // signal, while still ignoring possible frame edges.
            val useCentralBand = usableRowCount < max(2, h / 6)
            val margin = max(1, h / 10)
            val col = IntArray(w)
            for (x in 0 until w) {
                var c = 0
                for (y in 0 until h) {
                    if (useCentralBand && (y < margin || y >= h - margin)) continue
                    if (!useCentralBand && !usableRows[y]) continue
                    val g = gray[y * w + x]
                    if (if (lightInk) g > otsu else g < otsu) c++
                }
                col[x] = c
            }
            return col
        }
        // Тёмные чернила на светлом фоне; если фон тёмный (светлый текст) —
        // большинство колонок «активны», тогда инвертируем.
        var ink = inkColumns(false)
        if (ink.count { it > 0 } > w * 7 / 10) ink = inkColumns(true)
        val runs = mutableListOf<IntArray>()
        var start = -1
        for (x in 0 until w) {
            if (ink[x] > 0) {
                if (start < 0) start = x
            } else if (start >= 0) {
                runs.add(intArrayOf(start, x - 1))
                start = -1
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, w - 1))
        if (runs.size < 2) return listOf(crop)
        val gaps = IntArray(runs.size - 1) { i -> runs[i + 1][0] - runs[i][1] - 1 }
        val positive = gaps.filter { it > 0 }.sorted()
        val median = if (positive.isEmpty()) 1.0 else positive[positive.size / 2].toDouble()
        val threshold = max(5, round(median * 1.7).toInt())
        var splitAfter = 0
        val groups = mutableListOf<IntArray>()
        var groupStart = runs[0][0]
        for (i in gaps.indices) {
            if (gaps[i] >= threshold) {
                groups.add(intArrayOf(groupStart, runs[i][1]))
                groupStart = runs[i + 1][0]
                splitAfter++
            }
        }
        if (splitAfter == 0) return listOf(crop)
        groups.add(intArrayOf(groupStart, runs[runs.size - 1][1]))
        if (groups.size <= 1) return listOf(crop)
        return groups.map { g ->
            val left = max(0, g[0] - 4)
            val right = min(w, g[1] + 5)
            Bitmap.createBitmap(crop, left, 0, max(1, right - left), h)
        }
    }

    /**
     * Пословная правка омоглифов и фильтр мусора «словарной лесенкой».
     * Пословная — чтобы чистая латынь («SOS», «Wi-Fi») оставалась латынью и
     * TTS не читал её по буквам внутри русских слов.
     */
    private fun cleanRecognition(text: String): String {
        // Склеиваем переносы с дефисом ДО того, как postprocessing превратит
        // строковые границы в пробелы. Иначе «НЕУПРАВ-\nЛЯЕМЫЙ» остаётся в
        // интерфейсе как ложное «НЕУПРАВ- ЛЯЕМЫЙ».
        val cleaned = OcrTextCleaner.normalizeLocalCyrillicCaption(text).trim()
        return if (
            cleaned.isEmpty() ||
            OcrTextCleaner.looksLikeDictionaryRamp(cleaned) ||
            !OcrTextCleaner.isAcceptableCyrillicOcrText(cleaned)
        ) {
            ""
        } else {
            cleaned
        }
    }

    private fun detectTextBoxes(image: Bitmap): List<TextBox> {
        detectorCanvas.drawColor(Color.WHITE)
        val scale = min(DETECTOR_SIZE.toFloat() / image.width, DETECTOR_SIZE.toFloat() / image.height)
        val scaledWidth = max(1, (image.width * scale).toInt())
        val scaledHeight = max(1, (image.height * scale).toInt())
        val offsetX = (DETECTOR_SIZE - scaledWidth) / 2
        val offsetY = (DETECTOR_SIZE - scaledHeight) / 2
        detectorCanvas.drawBitmap(
            image,
            null,
            Rect(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight),
            detectorPaint,
        )
        detectorBitmap.getPixels(detectorPixels, 0, DETECTOR_SIZE, 0, 0, DETECTOR_SIZE, DETECTOR_SIZE)

        var out = 0
        detectorPixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // Модели PaddleOCR обучены в BGR (OpenCV): синий канал идёт первым,
            // константы нормализации — по позициям каналов, как в rapidocr.
            detectorFloats[out++] = (b / 255f - 0.485f) / 0.229f
            detectorFloats[out++] = (g / 255f - 0.456f) / 0.224f
            detectorFloats[out++] = (r / 255f - 0.406f) / 0.225f
        }
        detectorInput.writeFloat(detectorFloats)
        detector.run(listOf(detectorInput), listOf(detectorOutput))
        val probability = detectorOutput.readFloat()
        if (probability.size < DETECTOR_SIZE * DETECTOR_SIZE) return emptyList()

        visited.fill(false)
        val boxes = mutableListOf<Rect>()
        val limit = DETECTOR_SIZE * DETECTOR_SIZE
        for (start in 0 until limit) {
            if (visited[start] || probability[start] < DETECTOR_THRESHOLD) continue
            var head = 0
            var tail = 0
            componentQueue[tail++] = start
            visited[start] = true
            var minX = DETECTOR_SIZE
            var minY = DETECTOR_SIZE
            var maxX = 0
            var maxY = 0
            var area = 0
            while (head < tail) {
                val index = componentQueue[head++]
                val x = index % DETECTOR_SIZE
                val y = index / DETECTOR_SIZE
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                area++
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until DETECTOR_SIZE || ny !in 0 until DETECTOR_SIZE) continue
                    val next = ny * DETECTOR_SIZE + nx
                    if (!visited[next] && probability[next] >= DETECTOR_THRESHOLD) {
                        visited[next] = true
                        componentQueue[tail++] = next
                    }
                }
            }
            if (area < MIN_COMPONENT_AREA || maxX - minX < 3 || maxY - minY < 3) continue
            val expandX = max(3, ((maxX - minX) * 0.16f).toInt())
            val expandY = max(2, ((maxY - minY) * 0.20f).toInt())
            val left = (((minX - expandX - offsetX) / scale).toInt()).coerceIn(0, image.width - 1)
            val top = (((minY - expandY - offsetY) / scale).toInt()).coerceIn(0, image.height - 1)
            val right = (ceil((maxX + expandX - offsetX) / scale).toInt()).coerceIn(left + 1, image.width)
            val bottom = (ceil((maxY + expandY - offsetY) / scale).toInt()).coerceIn(top + 1, image.height)
            if (right - left >= 6 && bottom - top >= 6) boxes += Rect(left, top, right, bottom)
        }
        return mergeBoxes(boxes).take(MAX_TEXT_BOXES).map(::TextBox)
    }

    private fun mergeBoxes(source: List<Rect>): List<Rect> {
        val boxes = source.sortedWith(compareBy({ it.top }, { it.left })).map(::Rect).toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    val a = boxes[i]
                    val b = boxes[j]
                    val overlapY = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
                    val minHeight = min(a.height(), b.height()).coerceAtLeast(1)
                    val gapX = max(0, max(a.left, b.left) - min(a.right, b.right))
                    if (overlapY >= minHeight * 0.55f && gapX <= max(a.height(), b.height()) * 0.55f) {
                        a.union(b)
                        boxes.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return boxes.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun pad(rect: Rect, width: Int, height: Int): Rect {
        val px = max(2, (rect.width() * 0.04f).toInt())
        val py = max(2, (rect.height() * 0.12f).toInt())
        return Rect(
            (rect.left - px).coerceAtLeast(0),
            (rect.top - py).coerceAtLeast(0),
            (rect.right + px).coerceAtMost(width),
            (rect.bottom + py).coerceAtMost(height),
        )
    }

    private fun recognizeCrop(crop: Bitmap): Recognition {
        val candidates = mutableListOf(
            runRecognizer(crop, primary, primaryInput, primaryOutput, primaryChars, RecognitionModel.V3),
        )
        if (candidates.last().confidence < PRIMARY_CONFIDENCE) {
            val contrast = createHighContrast(crop)
            try {
                val alternate = runRecognizer(
                    contrast,
                    primary,
                    primaryInput,
                    primaryOutput,
                    primaryChars,
                    RecognitionModel.V3,
                )
                candidates += alternate
            } finally {
                contrast.recycle()
            }
        }
        val secondModel = verifier
        val secondInput = verifierInput
        val secondOutput = verifierOutput
        val secondChars = verifierChars
        if (
            secondModel != null &&
            secondInput != null && secondOutput != null && secondChars != null
        ) {
            // Device regression showed that v3 may assign a high confidence to
            // Latin-shaped garbage in clean Cyrillic captions. Always compare
            // v5 rather than treating it as a low-confidence-only fallback.
            val verifierResult = runRecognizer(
                crop,
                secondModel,
                secondInput,
                secondOutput,
                secondChars,
                RecognitionModel.V5,
            )
            candidates += verifierResult
            if (verifierResult.confidence < PRIMARY_CONFIDENCE) {
                val contrast = createHighContrast(crop)
                try {
                    candidates += runRecognizer(
                        contrast,
                        secondModel,
                        secondInput,
                        secondOutput,
                        secondChars,
                        RecognitionModel.V5,
                    )
                } finally {
                    contrast.recycle()
                }
            }
        }
        return candidates.maxByOrNull(::candidateQuality) ?: Recognition("", 0f)
    }

    /**
     * PP-OCRv3 can report a high softmax score for Latin-shaped noise in comic
     * fonts. When v5 yields valid Cyrillic, prefer it over v3's softmax-only
     * choice. This is model ranking, not spelling replacement; only verified
     * boundary restoration occurs later in [cleanRecognition].
     */
    private fun candidateQuality(candidate: Recognition): Float {
        if (candidate.text.isBlank()) return Float.NEGATIVE_INFINITY
        val fitness = OcrTextCleaner.cyrillicFitness(candidate.text)
        val lengthBonus = min(candidate.text.count(Char::isLetter) * 0.01f, 0.12f)
        val verifierBonus = if (
            candidate.model == RecognitionModel.V5 &&
            OcrTextCleaner.isAcceptableCyrillicOcrText(
                OcrTextCleaner.fixLookalikesPerWord(candidate.text),
            )
        ) {
            VERIFIER_CYRILLIC_BONUS
        } else {
            0f
        }
        return candidate.confidence * fitness + lengthBonus + verifierBonus
    }

    private fun createHighContrast(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.5f, 0f, 0f, 0f, -45f,
                        0f, 1.5f, 0f, 0f, -45f,
                        0f, 0f, 1.5f, 0f, -45f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        Canvas(result).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return result
    }

    private fun runRecognizer(
        crop: Bitmap,
        model: CompiledModel,
        input: TensorBuffer,
        output: TensorBuffer,
        chars: List<String>,
        recognitionModel: RecognitionModel,
    ): Recognition {
        recognizerCanvas.drawColor(Color.rgb(128, 128, 128))
        val scale = RECOGNIZER_HEIGHT.toFloat() / crop.height.coerceAtLeast(1)
        val targetWidth = (crop.width * scale).toInt().coerceIn(1, RECOGNIZER_WIDTH)
        recognizerCanvas.drawBitmap(
            crop,
            null,
            Rect(0, 0, targetWidth, RECOGNIZER_HEIGHT),
            recognizerPaint,
        )
        recognizerBitmap.getPixels(
            recognizerPixels,
            0,
            RECOGNIZER_WIDTH,
            0,
            0,
            RECOGNIZER_WIDTH,
            RECOGNIZER_HEIGHT,
        )
        var out = 0
        recognizerPixels.forEach { pixel ->
            // BGR, как в эталонной реализации из репозитория моделей:
            // «the original Paddle model was trained with BGR order».
            recognizerFloats[out++] = ((pixel and 0xFF) / 255f - 0.5f) / 0.5f
            recognizerFloats[out++] = (((pixel shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            recognizerFloats[out++] = (((pixel shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
        }
        input.writeFloat(recognizerFloats)
        model.run(listOf(input), listOf(output))
        val values = output.readFloat()
        // Число классов берём из фактического размера выхода: recognizer'ы
        // пака отдают [1, 40, C] (README репозитория моделей: «для recognizer
        // output — [1, 40, 165]»), то есть C = values.size / 40. Старый код
        // брал C из словаря (163 строки + 1 = 164), окно чтения дрейфовало
        // на один класс за шаг времени, и decode выдавал «лесенку» словаря —
        // «0123456789», «ABCDEFGHIJKLM» — вместо текста. Это и был «баг
        // кодировки» локального OCR.
        val classes = if (values.size % RECOGNIZER_STEPS == 0 && values.size / RECOGNIZER_STEPS >= 2) {
            values.size / RECOGNIZER_STEPS
        } else {
            chars.size + 1
        }
        return decodeCtc(values, chars, classes, recognitionModel)
    }

    private fun decodeCtc(
        values: FloatArray,
        chars: List<String>,
        classes: Int,
        recognitionModel: RecognitionModel,
    ): Recognition {
        if (classes <= 1 || values.size < classes) return Recognition("", 0f)
        val steps = values.size / classes
        val text = StringBuilder(steps)
        var previous = -1
        var confidenceSum = 0f
        var confidenceCount = 0
        for (step in 0 until steps) {
            val base = step * classes
            var bestIndex = 0
            var bestScore = values[base]
            for (index in 1 until classes) {
                val char = chars.getOrNull(index - 1).orEmpty()
                if (!allowed(char)) continue
                val score = values[base + index]
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex != 0 && bestIndex != previous) {
                chars.getOrNull(bestIndex - 1)?.let(text::append)
                confidenceSum += bestScore
                confidenceCount++
            }
            previous = bestIndex
        }
        return Recognition(
            text = text.toString().trim(),
            confidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount,
            model = recognitionModel,
        )
    }

    /**
     * Символы, которые модели разрешено выдавать.
     *
     * Латиница раньше была запрещена, и слова вроде «SOS», «BMW», «Wi-Fi»
     * или «3D» терялись целиком: в CTC на шаге запрещённого символа
     * побеждает другой класс или blank, поэтому рвётся всё слово, а не
     * один знак. В русской манге латиница встречается постоянно —
     * звукоподражания, названия, надписи на вывесках.
     */
    private fun allowed(value: String): Boolean {
        if (value.length != 1) return false
        val char = value[0]
        return char in '\u0400'..'\u052F' ||
            char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char.isDigit() ||
            char.isWhitespace() ||
            char in ALLOWED_PUNCTUATION
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { if (::detectorInput.isInitialized) detectorInput.close() }
        runCatching { if (::detectorOutput.isInitialized) detectorOutput.close() }
        runCatching { if (::primaryInput.isInitialized) primaryInput.close() }
        runCatching { if (::primaryOutput.isInitialized) primaryOutput.close() }
        runCatching { verifierInput?.close() }
        runCatching { verifierOutput?.close() }
        verifierInput = null
        verifierOutput = null
        runCatching { if (::detector.isInitialized) detector.close() }
        runCatching { if (::primary.isInitialized) primary.close() }
        runCatching { verifier?.close() }
        verifier = null
        if (::detectorBitmap.isInitialized && !detectorBitmap.isRecycled) detectorBitmap.recycle()
        if (::recognizerBitmap.isInitialized && !recognizerBitmap.isRecycled) recognizerBitmap.recycle()
        initialized = false
    }

    companion object {
        private const val TAG = "CyrillicOcr"
        const val PACK = "cyrillic_ocr"
        const val DETECTOR_PATH = "cyrillic_ocr/detector.tflite"
        const val PRIMARY_PATH = "cyrillic_ocr/recognizer_v3.tflite"
        const val VERIFIER_PATH = "cyrillic_ocr/recognizer_v5.tflite"
        const val PRIMARY_DICT_PATH = "cyrillic_ocr/dict_v3.txt"
        const val VERIFIER_DICT_PATH = "cyrillic_ocr/dict_v5.txt"

        private const val DETECTOR_SIZE = 736
        private const val RECOGNIZER_WIDTH = 320
        private const val RECOGNIZER_HEIGHT = 48
        // Число шагов по времени в выходе recognizer'ов пака ([1, 40, C]).
        private const val RECOGNIZER_STEPS = 40
        // Порог активации детектора. Понижен с 0.28: на тонком шрифте и
        // светлых баллонах часть строк не набирала 0.28 и терялась целиком.
        private const val DETECTOR_THRESHOLD = 0.20f

        // Минимальная площадь связной области. Понижена с 28: короткие
        // реплики («Да!», «Что?..») не дотягивали и пропускались.
        private const val MIN_COMPONENT_AREA = 16
        private const val MAX_TEXT_BOXES = 96

        // Ниже этого — пробуем распознать ещё раз с повышенным контрастом.
        private const val PRIMARY_CONFIDENCE = 0.90f

        // Prefer valid Cyrillic from PP-OCRv5 over v3's raw-softmax-only
        // winner. Calibrated against device captures of compact text panels.
        private const val VERIFIER_CYRILLIC_BONUS = 0.20f

        // Цельный кроп получает небольшое преимущество лишь когда его
        // слитный CTC-вывод уже разделяется консервативным списком слов.
        private const val WHOLE_LINE_BOUNDARY_BONUS = 0.08f

        // Результат слабее этого порога отбрасывается совсем. Раньше такого
        // порога не было и роль «пола отсечения» играл PRIMARY_CONFIDENCE:
        // верно прочитанные, но неуверенные слова выпадали из текста.
        // Лучше показать слово с опечаткой, чем молча его потерять.
        private const val MIN_ACCEPT_CONFIDENCE = 0.25f
        private const val ALLOWED_PUNCTUATION = " .,!?;:-()[]{}\"'«»„“”%№+/=…—–"
    }
}
