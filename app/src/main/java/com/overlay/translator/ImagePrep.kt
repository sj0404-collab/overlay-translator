package com.overlay.translator

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ImagePrep {

    data class Bubble(val rect: Rect, val lightBg: Boolean)

    fun prepareForOcr(src: Bitmap): Bitmap {
        val w = src.width.coerceAtLeast(2)
        val h = src.height.coerceAtLeast(2)
        val scale = when {
            max(w, h) < 220 -> 2.5f
            max(w, h) < 400 -> 1.6f
            else -> 1f
        }
        val nw = (w * scale).toInt().coerceIn(32, 1600)
        val nh = (h * scale).toInt().coerceIn(32, 1600)
        val bmp = if (scale != 1f) Bitmap.createScaledBitmap(src, nw, nh, true) else src.copy(Bitmap.Config.ARGB_8888, true)
        val n = nw * nh
        val px = IntArray(n)
        bmp.getPixels(px, 0, nw, 0, 0, nw, nh)

        var sum = 0L
        val samples = IntArray(min(400, n))
        val step = max(1, n / samples.size)
        var si = 0
        var i = 0
        while (i < n && si < samples.size) {
            val y = luma(px[i])
            samples[si++] = y
            sum += y
            i += step
        }
        samples.sort()
        val med = samples[samples.size / 2]
        val lightBg = med > 128

        for (p in px.indices) {
            var y = luma(px[p])
            if (!lightBg) y = 255 - y
            // contrast stretch around mid
            val c = ((y - 128) * 1.45f + 128).toInt().coerceIn(0, 255)
            val bin = if (c < 150) 0 else 255
            px[p] = Color.rgb(bin, bin, bin)
        }
        bmp.setPixels(px, 0, nw, 0, 0, nw, nh)
        return bmp
    }

    fun findBubbles(src: Bitmap): List<Bubble> {
        val maxSide = 360
        val scale = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(8)
        val h = (src.height * scale).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)

        // mark near-flat light or dark panels (speech balloons)
        val mask = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val y0 = luma(px[i])
                val v = abs(y0 - luma(px[i - 1])) + abs(y0 - luma(px[i + 1])) +
                    abs(y0 - luma(px[i - w])) + abs(y0 - luma(px[i + w]))
                val flat = v < 48
                val panel = y0 > 200 || y0 < 40
                mask[i] = flat && panel
            }
        }
        val seen = BooleanArray(w * h)
        val out = ArrayList<Bubble>()
        val qx = IntArray(w * h)
        val qy = IntArray(w * h)
        for (sy in 0 until h) {
            for (sx in 0 until w) {
                val s = sy * w + sx
                if (!mask[s] || seen[s]) continue
                var qh = 0
                var qt = 0
                qx[qh] = sx; qy[qh] = sy; qh++
                seen[s] = true
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                var cnt = 0
                var light = 0
                while (qt < qh) {
                    val x = qx[qt]; val y = qy[qt]; qt++
                    cnt++
                    if (luma(px[y * w + x]) > 128) light++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val ni = ny * w + nx
                        if (!mask[ni] || seen[ni]) continue
                        seen[ni] = true
                        qx[qh] = nx; qy[qh] = ny; qh++
                    }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val area = bw * bh
                if (cnt < 80 || area < 120) continue
                if (bw < 18 || bh < 12) continue
                if (bw > w * 0.95 && bh > h * 0.95) continue
                val fill = cnt.toFloat() / area
                if (fill < 0.35f) continue
                val inv = 1f / scale
                val pad = 4
                val r = Rect(
                    (minX * inv).toInt() - pad,
                    (minY * inv).toInt() - pad,
                    ((maxX + 1) * inv).toInt() + pad,
                    ((maxY + 1) * inv).toInt() + pad
                )
                r.left = r.left.coerceIn(0, src.width - 1)
                r.top = r.top.coerceIn(0, src.height - 1)
                r.right = r.right.coerceIn(r.left + 1, src.width)
                r.bottom = r.bottom.coerceIn(r.top + 1, src.height)
                out.add(Bubble(r, light * 2 >= cnt))
            }
        }
        out.sortByDescending { b -> b.rect.width() * b.rect.height() }
        val kept = ArrayList<Bubble>()
        for (b in out) {
            var overlap = false
            for (k in kept) {
                if (iou(k.rect, b.rect) > 0.55f) {
                    overlap = true
                    break
                }
            }
            if (overlap) continue
            kept.add(b)
            if (kept.size >= 8) break
        }
        kept.sortWith(compareBy({ b: Bubble -> b.rect.top }, { b: Bubble -> b.rect.left }))
        return kept
    }

    fun cleanOcr(raw: String, english: Boolean): String {
        var s = raw.replace('\u000c', ' ')
        s = s.replace('|', 'I')
        s = s.replace('\n', ' ')
        s = s.replace('\r', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(?i)overlay\\s*tran.*"), "")
        s = s.replace(Regex("(?i)mymemory"), "")
        if (english) {
            val sb = StringBuilder()
            for (ch in s) {
                val ok = ch.isLetterOrDigit() || ch == ' ' || ch == '.' || ch == ',' ||
                    ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == '\'' ||
                    ch == '"' || ch == '(' || ch == ')' || ch == '-'
                sb.append(if (ok && ch.toInt() < 128) ch else ' ')
            }
            s = sb.toString()
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun luma(c: Int): Int {
        val r = c shr 16 and 255
        val g = c shr 8 and 255
        val b = c and 255
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun iou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val iw = (r - l).coerceAtLeast(0)
        val ih = (bot - t).coerceAtLeast(0)
        val inter = iw * ih
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0) 0f else inter.toFloat() / u
    }
}
