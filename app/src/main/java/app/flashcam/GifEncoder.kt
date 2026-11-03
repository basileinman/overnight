package app.flashcam

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Small looping-GIF writer for burst exports. One shared 256-color palette, picked from the most
 * common colors across all frames (film grain hides the banding, so no dithering).
 */
object GifEncoder {

    fun encode(frames: List<Bitmap>, delayMs: Int, out: OutputStream) {
        require(frames.isNotEmpty())
        val w = frames[0].width; val h = frames[0].height
        val pixels = frames.map { f -> IntArray(w * h).also { f.getPixels(it, 0, w, 0, 0, w, h) } }

        // palette: popularity of 15-bit colors across all frames
        val counts = IntArray(32768)
        for (px in pixels) for (c in px) counts[key(c)]++
        val top = (0 until 32768).filter { counts[it] > 0 }.sortedByDescending { counts[it] }.take(256)
        val pal = IntArray(256) { i -> top.getOrNull(i)?.let { unkey(it) } ?: 0 }
        val n = top.size.coerceAtLeast(1)
        // lookup: every 15-bit color -> nearest palette entry (filled lazily)
        val lut = IntArray(32768) { -1 }
        fun nearest(k: Int): Int {
            val cached = lut[k]; if (cached >= 0) return cached
            val c = unkey(k); val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            var best = 0; var bd = Int.MAX_VALUE
            for (i in 0 until n) {
                val p = pal[i]
                val dr = (p shr 16 and 255) - r; val dg = (p shr 8 and 255) - g; val db = (p and 255) - b
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bd) { bd = d; best = i }
            }
            lut[k] = best; return best
        }

        out.write("GIF89a".toByteArray())
        short(out, w); short(out, h)
        out.write(0xF7); out.write(0); out.write(0)          // global color table, 256 entries
        for (c in pal) { out.write(c shr 16 and 255); out.write(c shr 8 and 255); out.write(c and 255) }
        // loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))

        for (px in pixels) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))  // graphic control: no disposal, no transparency
            short(out, (delayMs / 10).coerceAtLeast(2)); out.write(0); out.write(0)
            out.write(0x2C); short(out, 0); short(out, 0); short(out, w); short(out, h); out.write(0)
            val idx = ByteArray(px.size) { i -> nearest(key(px[i])).toByte() }
            out.write(8)
            lzw(idx, out)
        }
        out.write(0x3B)
    }

    private fun key(c: Int) = ((c shr 19 and 31) shl 10) or ((c shr 11 and 31) shl 5) or (c shr 3 and 31)
    private fun unkey(k: Int): Int {
        val r = (k shr 10 and 31) * 255 / 31; val g = (k shr 5 and 31) * 255 / 31; val b = (k and 31) * 255 / 31
        return (r shl 16) or (g shl 8) or b
    }
    private fun short(o: OutputStream, v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }

    /** Standard GIF LZW with 8-bit root, variable code size up to 12 bits, written in 255-byte blocks. */
    private fun lzw(data: ByteArray, out: OutputStream) {
        val clear = 256; val eoi = 257
        val buf = ByteArrayOutputStream()
        var bitBuf = 0; var bitCount = 0
        var codeSize = 9
        var next = 258
        var clearFlag = false
        // mirrors the classic compress/AnimatedGifEncoder code-size rules
        fun emit(code: Int) {
            bitBuf = bitBuf or (code shl bitCount); bitCount += codeSize
            while (bitCount >= 8) { buf.write(bitBuf and 255); bitBuf = bitBuf ushr 8; bitCount -= 8 }
            if (clearFlag) { codeSize = 9; clearFlag = false }
            else if (next > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
        }
        val dict = HashMap<Int, Int>(8192)
        emit(clear)
        var prefix = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 255
            val combo = (prefix shl 8) or k
            val found = dict[combo]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            prefix = k
            if (next < 4096) dict[combo] = next++
            else { dict.clear(); next = 258; clearFlag = true; emit(clear) }
        }
        emit(prefix)
        emit(eoi)
        if (bitCount > 0) buf.write(bitBuf and 255)
        val bytes = buf.toByteArray()
        var p = 0
        while (p < bytes.size) {
            val len = minOf(255, bytes.size - p)
            out.write(len); out.write(bytes, p, len); p += len
        }
        out.write(0)
    }
}
