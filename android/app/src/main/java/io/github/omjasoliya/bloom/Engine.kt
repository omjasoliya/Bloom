package io.github.omjasoliya.bloom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Petal(val key: String, val label: String)

class Outcome(val title: String, val saved: List<Saved>, val detail: String? = null)

/** What Bloom can do with some files, and doing it. Everything runs on the phone. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object Engine {
    val MODES = listOf("convert", "tools", "actions")
    val TITLES = mapOf("convert" to "Convert", "tools" to "Tools", "actions" to "Actions", "fit" to "Fit size")

    // Forms (exams, jobs, passports, KYC) ask for things like "photo under 50 KB, 200x230 px".
    val FIT_SIZES = listOf(
        Petal("20", "20 KB"), Petal("50", "50 KB"), Petal("100", "100 KB"), Petal("200", "200 KB"),
        Petal("500", "500 KB"), Petal("custom", "Custom"))

    private val IMAGE_TOOLS = listOf(
        Petal("fit", "Fit size"), Petal("compress", "Compress"), Petal("half", "Half size"), Petal("rotate", "Rotate"),
        Petal("clean", "No metadata"), Petal("gray", "Grayscale"))
    private val ACTIONS = listOf(
        Petal("zip", "Zip"), Petal("save", "To Downloads"), Petal("share", "Share"), Petal("name", "Copy name"))
    private val SUFFIX = mapOf("compress" to "small", "half" to "half", "rotate" to "rotated", "clean" to "clean", "gray" to "gray")
    private val TOOL_TITLES = mapOf(
        "compress" to "Compressed", "half" to "Half size", "rotate" to "Rotated", "clean" to "Metadata removed",
        "gray" to "Grayscale", "720p" to "Made 720p", "mute" to "Muted")

    fun petals(mode: String, inputs: List<Input>): List<Petal> = when (mode) {
        "convert" -> convertFor(inputs)
        "tools" -> toolsFor(inputs)
        "fit" -> if (kindOf(inputs) == Kind.IMAGE) FIT_SIZES else emptyList()
        else -> ACTIONS
    }

    /** The one kind all the files share, or OTHER for a mix. */
    private fun kindOf(inputs: List<Input>) = inputs.map { it.kind }.distinct().singleOrNull() ?: Kind.OTHER

    private fun norm(ext: String) = if (ext == "jpeg") "jpg" else ext

    private fun convertFor(inputs: List<Input>): List<Petal> {
        val outs = when (kindOf(inputs)) {
            Kind.IMAGE -> listOf("jpg", "png", "webp", "pdf")
            Kind.PDF -> listOf("jpg", "png")
            Kind.VIDEO -> listOf("mp4", "m4a")
            Kind.AUDIO -> listOf("m4a")
            Kind.OTHER -> emptyList()
        }
        val from = inputs.map { norm(it.ext) }.toSet()
        return outs.filter { from != setOf(it) }.map { Petal(it, it.uppercase()) }
    }

    private fun toolsFor(inputs: List<Input>): List<Petal> = when (kindOf(inputs)) {
        Kind.IMAGE -> IMAGE_TOOLS
        Kind.PDF -> if (inputs.size > 1) listOf(Petal("merge", "Merge")) else listOf(Petal("split", "Split pages"))
        Kind.VIDEO -> listOf(Petal("720p", "720p"), Petal("mute", "Mute"))
        else -> emptyList()
    }

    /** Runs a petal on a background thread. [progress] gets 0-100 when it's known. */
    fun run(context: Context, mode: String, key: String, inputs: List<Input>, progress: (Int) -> Unit): Outcome {
        val ctx = context.applicationContext
        return when (mode) {
            "convert" -> Outcome("Converted to ${key.uppercase()}", convert(ctx, key, inputs, progress))
            "tools" -> tool(ctx, key, inputs, progress)
            "fit" -> fit(ctx, key, inputs, progress)
            else -> action(ctx, key, inputs)
        }
    }

    // ---- convert ----

    private fun convert(ctx: Context, to: String, inputs: List<Input>, progress: (Int) -> Unit): List<Saved> {
        val kind = kindOf(inputs)
        if (kind == Kind.IMAGE && to == "pdf") return listOf(imagesToPdf(ctx, inputs, progress))
        return inputs.flatMapIndexed { i, input ->
            val step = { p: Int -> progress((i * 100 + p) / inputs.size) }
            when (kind) {
                Kind.IMAGE -> listOf(Downloads.save(ctx, "${input.stem}.$to") { encode(decode(ctx, input), to, it) })
                Kind.PDF -> pdfToImages(ctx, input, to)
                else -> listOf(transform(ctx, input, "${input.stem}.$to", removeVideo = to == "m4a", progress = step))
            }.also { step(100) }
        }
    }

    private fun imagesToPdf(ctx: Context, inputs: List<Input>, progress: (Int) -> Unit): Saved {
        PDFBoxResourceLoader.init(ctx)
        val first = inputs[0].stem
        val name = if (inputs.size == 1) "$first.pdf" else "$first and ${inputs.size - 1} more.pdf"
        PDDocument().use { doc ->
            inputs.forEachIndexed { i, input ->
                val bitmap = decode(ctx, input, maxSide = 2480)
                val image = JPEGFactory.createFromImage(doc, opaque(bitmap), 0.85f)
                // The long side is A4's (842 pt); the page keeps the photo's shape.
                val scale = 842f / max(bitmap.width, bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, w, h) }
                bitmap.recycle()
                progress((i + 1) * 100 / inputs.size)
            }
            return Downloads.save(ctx, name) { doc.save(it) }
        }
    }

    private fun pdfToImages(ctx: Context, input: Input, to: String): List<Saved> {
        val copy = cacheCopy(ctx, input)         // PdfRenderer needs a real, seekable file
        try {
            val renderer = try {
                PdfRenderer(ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
            } catch (e: SecurityException) {
                throw IOException("${input.name} is password-protected.")
            }
            return renderer.use { pdf ->
                (0 until pdf.pageCount).map { n ->
                    pdf.openPage(n).use { page ->
                        val scale = min(2f, 3000f / max(page.width, page.height))      // about 144 dpi
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt(), (page.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val name = if (pdf.pageCount == 1) "${input.stem}.$to" else "${input.stem}-p${n + 1}.$to"
                        Downloads.save(ctx, name) { encode(bitmap, to, it) }.also { bitmap.recycle() }
                    }
                }
            }
        } finally {
            copy.delete()
        }
    }

    // ---- fit size ----

    /** [key] is "50" or "50:413:531" (KB, then an optional exact width and height in px). */
    private fun fit(ctx: Context, key: String, inputs: List<Input>, progress: (Int) -> Unit): Outcome {
        val parts = key.split(":")
        val kb = parts[0].toInt()
        val width = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val height = parts.getOrNull(2)?.toIntOrNull() ?: 0
        var detail = ""
        val saved = inputs.mapIndexed { i, input ->
            val image = sized(opaque(decode(ctx, input, maxSide = 6000)), width, height)
            val (bytes, w, h) = fitJpeg(image, kb * 1024, exact = width > 0 || height > 0)
                ?: throw IOException("${input.name} can't be made small enough for $kb KB" +
                    (if (width > 0 || height > 0) " at ${width}×${height} px" else "") + ". Try a larger size.")
            detail = "${humanSize(bytes.size.toLong())} · $w×$h px"
            progress((i + 1) * 100 / inputs.size)
            Downloads.save(ctx, "${input.stem}-${kb}kb.jpg") { it.write(bytes) }
        }
        return Outcome("Fits in $kb KB", saved, if (saved.size == 1) detail else null)
    }

    /** Exactly width x height (cropped from the centre), or that width / height keeping the shape. */
    private fun sized(src: Bitmap, width: Int, height: Int): Bitmap = when {
        width > 0 && height > 0 -> {
            val scale = max(width.toFloat() / src.width, height.toFloat() / src.height)
            val w = max(width, (src.width * scale).roundToInt())
            val h = max(height, (src.height * scale).roundToInt())
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            Bitmap.createBitmap(scaled, (w - width) / 2, (h - height) / 2, width, height)
        }
        width > 0 -> Bitmap.createScaledBitmap(src, width, max(1, (src.height * width.toFloat() / src.width).roundToInt()), true)
        height > 0 -> Bitmap.createScaledBitmap(src, max(1, (src.width * height.toFloat() / src.height).roundToInt()), height, true)
        else -> src
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Highest JPEG quality in lo..hi that fits in limit bytes, with that quality. */
    private fun bestQuality(bitmap: Bitmap, limit: Int, lo: Int, hi: Int): Pair<ByteArray, Int>? {
        var low = lo
        var high = hi
        var best: Pair<ByteArray, Int>? = null
        while (low <= high) {
            val mid = (low + high) / 2
            val data = jpeg(bitmap, mid)
            if (data.size <= limit) {
                best = Pair(data, mid)
                low = mid + 1
            } else high = mid - 1
        }
        return best
    }

    /** JPEG bytes under limit, keeping as many pixels and as much quality as fit.
     *  With exact dimensions only the quality can give way. */
    private fun fitJpeg(src: Bitmap, limit: Int, exact: Boolean): Triple<ByteArray, Int, Int>? {
        if (exact) return bestQuality(src, limit, 5, 100)?.let { Triple(it.first, src.width, src.height) }
        // Start at a size that should fit at good quality; never larger than the original.
        var scale = min(1.0, sqrt(limit * 8 / 1.8 / (src.width.toDouble() * src.height)))
        var best: Triple<ByteArray, Int, Int>? = null
        var cur = src
        repeat(10) {
            cur = if (scale >= 1.0) src else Bitmap.createScaledBitmap(
                src, max(16, (src.width * scale).roundToInt()), max(16, (src.height * scale).roundToInt()), true)
            val found = bestQuality(cur, limit, 40, 92)
            if (found != null) {
                best = Triple(found.first, cur.width, cur.height)
                if (found.second < 88 || scale >= 1.0) return best
                scale = min(1.0, scale * 1.2)          // room to spare: try more pixels
            } else {
                if (best != null) return best
                if (min(cur.width, cur.height) <= 16) return bestQuality(cur, limit, 5, 39)?.let { Triple(it.first, cur.width, cur.height) }
                scale *= (sqrt(limit.toDouble() / jpeg(cur, 40).size) * 0.95).coerceIn(0.3, 0.95)
            }
        }
        return best ?: bestQuality(cur, limit, 5, 39)?.let { Triple(it.first, cur.width, cur.height) }
    }

    // ---- tools ----

    private fun tool(ctx: Context, key: String, inputs: List<Input>, progress: (Int) -> Unit): Outcome {
        when (key) {
            "merge" -> return Outcome("Merged ${inputs.size} PDFs", listOf(mergePdfs(ctx, inputs)))
            "split" -> splitPdf(ctx, inputs[0]).let { return Outcome("Split into ${it.size} pages", it) }
        }
        val saved = inputs.mapIndexed { i, input ->
            val step = { p: Int -> progress((i * 100 + p) / inputs.size) }
            when (key) {
                "720p" -> transform(ctx, input, "${input.stem}-720p.mp4", height = 720, progress = step)
                "mute" -> transform(ctx, input, "${input.stem}-muted.mp4", removeAudio = true, progress = step)
                else -> editImage(ctx, key, input)
            }.also { step(100) }
        }
        return Outcome(TOOL_TITLES[key] ?: "Done", saved)
    }

    private fun editImage(ctx: Context, key: String, input: Input): Saved {
        val src = decode(ctx, input, maxSide = if (key == "compress") 2560 else 8192)
        val out = when (key) {
            "half" -> Bitmap.createScaledBitmap(src, max(1, src.width / 2), max(1, src.height / 2), true)
            "rotate" -> Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(90f) }, true)
            "gray" -> grayscale(src)
            else -> src            // compress and clean: re-encoding drops the metadata
        }
        val same = norm(input.ext).takeIf { it in setOf("jpg", "png", "webp") } ?: if (out.hasAlpha()) "png" else "jpg"
        val ext = if (key == "compress" && same == "png") (if (out.hasAlpha()) "webp" else "jpg") else same
        val quality = if (key == "compress") 70 else 92
        return Downloads.save(ctx, "${input.stem}-${SUFFIX.getValue(key)}.$ext") { encode(out, ext, it, quality) }
    }

    private fun mergePdfs(ctx: Context, inputs: List<Input>): Saved {
        PDFBoxResourceLoader.init(ctx)
        val streams = inputs.map { it.open(ctx) }
        try {
            val merger = PDFMergerUtility()
            streams.forEach { merger.addSource(it) }
            return Downloads.save(ctx, "${inputs[0].stem}-merged.pdf") { out ->
                merger.destinationStream = out
                merger.mergeDocuments(MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
            }
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    private fun splitPdf(ctx: Context, input: Input): List<Saved> {
        PDFBoxResourceLoader.init(ctx)
        input.open(ctx).use { stream ->
            PDDocument.load(stream).use { doc ->
                if (doc.numberOfPages < 2) throw IOException("${input.name} has only one page.")
                return Splitter().split(doc).mapIndexed { i, part ->
                    part.use { p -> Downloads.save(ctx, "${input.stem}-p${i + 1}.pdf") { p.save(it) } }
                }
            }
        }
    }

    // ---- actions ----

    private fun action(ctx: Context, key: String, inputs: List<Input>): Outcome = when (key) {
        "zip" -> Outcome("Zipped", listOf(zip(ctx, inputs)))
        "save" -> Outcome("Saved to Downloads", inputs.map { input ->
            Downloads.save(ctx, input.name) { out -> input.open(ctx).use { it.copyTo(out) } }
        })
        else -> throw IllegalArgumentException("Unknown action $key")
    }

    private fun zip(ctx: Context, inputs: List<Input>): Saved {
        val name = if (inputs.size == 1) "${inputs[0].stem}.zip" else "Bloom ${inputs.size} files.zip"
        return Downloads.save(ctx, name) { out ->
            val zip = ZipOutputStream(out)
            val used = mutableSetOf<String>()
            for (input in inputs) {
                var entry = input.name
                var n = 2
                while (!used.add(entry)) entry = "${input.stem} (${n++})" + if (input.ext.isEmpty()) "" else ".${input.ext}"
                zip.putNextEntry(ZipEntry(entry))
                input.open(ctx).use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    // ---- images ----

    private fun decode(ctx: Context, input: Input, maxSide: Int = 8192): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, input.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            if (max(w, h) > maxSide) {
                val s = maxSide.toFloat() / max(w, h)
                decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap, ext: String, out: OutputStream, quality: Int = 92) {
        val format = when (ext) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val image = if (format == Bitmap.CompressFormat.JPEG) opaque(bitmap) else bitmap
        if (!image.compress(format, quality, out)) throw IOException("Couldn't write the image")
    }

    /** JPEG has no transparency: put see-through images on white instead of black. */
    private fun opaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    private fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // ---- video and audio ----

    /** Re-encodes with Media3 Transformer (H.264 / AAC in MP4), using the phone's own codecs. */
    private fun transform(
        ctx: Context, input: Input, name: String, removeAudio: Boolean = false, removeVideo: Boolean = false,
        height: Int? = null, progress: (Int) -> Unit,
    ): Saved {
        val temp = File(ctx.cacheDir, "bloom-${System.nanoTime()}.${name.substringAfterLast('.')}")
        val done = CountDownLatch(1)
        var failure: Exception? = null
        val main = Handler(Looper.getMainLooper())
        main.post {                  // Transformer lives on a thread with a Looper
            try {
                val effects = if (height == null) Effects.EMPTY
                else Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(height)))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(input.uri))
                    .setRemoveAudio(removeAudio)
                    .setRemoveVideo(removeVideo)
                    .setEffects(effects)
                    .build()
                val transformer = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            failure = exportException
                            done.countDown()
                        }
                    })
                    .build()
                transformer.start(item, temp.absolutePath)
                val holder = ProgressHolder()
                main.postDelayed(object : Runnable {
                    override fun run() {
                        if (done.count == 0L) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
                        main.postDelayed(this, 300)
                    }
                }, 300)
            } catch (e: Exception) {
                failure = e
                done.countDown()
            }
        }
        done.await()
        try {
            failure?.let { throw IOException("Couldn't process ${input.name}: ${it.message}", it) }
            return Downloads.saveFile(ctx, name, temp)
        } finally {
            temp.delete()
        }
    }

    private fun cacheCopy(ctx: Context, input: Input): File {
        val file = File(ctx.cacheDir, "bloom-${System.nanoTime()}.${input.ext.ifEmpty { "bin" }}")
        input.open(ctx).use { src -> file.outputStream().use { src.copyTo(it) } }
        return file
    }
}
