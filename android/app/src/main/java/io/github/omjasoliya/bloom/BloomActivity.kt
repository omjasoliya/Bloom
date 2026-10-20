package io.github.omjasoliya.bloom

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.InputType
import android.util.Size
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Home screen when opened from the launcher; the petal wheel once there are files
 *  (picked here, or shared from another app with Share → Bloom). */
class BloomActivity : Activity() {
    private lateinit var home: View
    private lateinit var stage: View
    private lateinit var wheel: WheelView
    private lateinit var thumb: ImageView
    private lateinit var thumbExt: TextView
    private lateinit var fileName: TextView
    private lateinit var fileInfo: TextView
    private lateinit var hint: TextView
    private lateinit var tabs: Map<String, TextView>
    private lateinit var result: View
    private lateinit var resultTitle: TextView
    private lateinit var resultBody: TextView
    private lateinit var openButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var doneButton: TextView
    private lateinit var notice: View
    private lateinit var noticeTitle: TextView
    private lateinit var noticeBody: TextView
    private lateinit var noticeButtons: View
    private lateinit var noticeYes: TextView
    private lateinit var noticeNo: TextView
    private lateinit var updatesToggle: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var inputs = emptyList<Input>()
    private var mode = "convert"
    private var saved = emptyList<Saved>()
    private var fromShare = false
    private var busy = false
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bloom)
        home = findViewById(R.id.home)
        stage = findViewById(R.id.stage)
        wheel = findViewById(R.id.wheel)
        thumb = findViewById(R.id.thumb)
        thumbExt = findViewById(R.id.thumb_ext)
        fileName = findViewById(R.id.file_name)
        fileInfo = findViewById(R.id.file_info)
        hint = findViewById(R.id.hint)
        tabs = mapOf(
            "convert" to findViewById(R.id.tab_convert),
            "tools" to findViewById(R.id.tab_tools),
            "actions" to findViewById(R.id.tab_actions))
        result = findViewById(R.id.result)
        resultTitle = findViewById(R.id.result_title)
        resultBody = findViewById(R.id.result_body)
        openButton = findViewById(R.id.open)
        shareButton = findViewById(R.id.share)
        doneButton = findViewById(R.id.done)
        notice = findViewById(R.id.notice)
        noticeTitle = findViewById(R.id.notice_title)
        noticeBody = findViewById(R.id.notice_body)
        noticeButtons = findViewById(R.id.notice_buttons)
        noticeYes = findViewById(R.id.notice_yes)
        noticeNo = findViewById(R.id.notice_no)
        updatesToggle = findViewById(R.id.updates_toggle)
        updatesToggle.setOnClickListener { setUpdates(Updater.choice(this) != "on") }

        thumb.clipToOutline = true
        findViewById<View>(R.id.choose).setOnClickListener { chooseFiles() }
        findViewById<View>(R.id.close).setOnClickListener { leave() }
        tabs.forEach { (m, tab) -> tab.setOnClickListener { showMode(m) } }
        wheel.onPick = ::pick
        wheel.onCenter = { if (mode == "fit") showMode("tools") else leave() }   // the flower steps back out of Fit size
        openButton.setOnClickListener { saved.singleOrNull()?.let(::open) }
        shareButton.setOnClickListener { share(saved.map { it.uri }, saved.map { it.mime }) }
        doneButton.setOnClickListener { closeResult() }
        handle(intent)
        if (Updater.due(this)) checkForUpdate(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            result.visibility == View.VISIBLE && !busy -> closeResult()
            stage.visibility == View.VISIBLE && mode == "fit" -> showMode("tools")
            stage.visibility == View.VISIBLE && !fromShare -> showHome()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun handle(intent: Intent?) {
        val uris = sharedUris(intent)
        if (uris.isNotEmpty()) {
            fromShare = true
            load(uris)
        } else {
            if (intent?.action == Intent.ACTION_SEND) toast("Bloom works with files: share a photo, PDF, video or any other file.")
            showHome()
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach { if (it is Uri) uris += it }
            else -> return emptyList()
        }
        if (uris.isEmpty()) intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        return uris.distinct()
    }

    private fun chooseFiles() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        @Suppress("DEPRECATION")
        startActivityForResult(pick, PICK_FILES)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) {
            fromShare = false
            load(uris)
        }
    }

    private fun load(uris: List<Uri>) {
        worker.execute {
            val list = uris.mapNotNull { runCatching { Input.from(this, it) }.getOrNull() }
            main.post { if (!isDestroyed) showFiles(list) }
        }
    }

    private fun showHome() {
        inputs = emptyList()
        showUpdatesToggle()
        if (Updater.choice(this) == null) askAboutUpdates()
        home.visibility = View.VISIBLE
        stage.visibility = View.GONE
        result.visibility = View.GONE
    }

    private fun showFiles(list: List<Input>) {
        if (list.isEmpty()) {
            toast("Bloom couldn't open that file.")
            if (fromShare) finish() else showHome()
            return
        }
        inputs = list
        fileName.text = list.singleOrNull()?.name ?: "${list.size} files"
        val total = if (list.any { it.size < 0 }) "" else humanSize(list.sumOf { it.size })
        fileInfo.text = listOf(total, "stays on your phone").filter { it.isNotEmpty() }.joinToString(" · ")
        loadThumb(list[0])
        home.visibility = View.GONE
        stage.visibility = View.VISIBLE
        result.visibility = View.GONE
        showMode(Engine.MODES.firstOrNull { Engine.petals(it, list).isNotEmpty() } ?: "actions")
    }

    private fun showMode(m: String) {
        if (busy) return
        mode = m
        tabs.forEach { (key, tab) ->
            val has = Engine.petals(key, inputs).isNotEmpty()
            tab.isEnabled = has
            tab.alpha = if (has) 1f else 0.35f
            val on = key == m || (key == "tools" && m == "fit")
            tab.isSelected = on
            tab.setTextColor(getColor(if (on) R.color.on_accent else R.color.ink))
        }
        wheel.setPetals(Engine.petals(m, inputs))
        val total = inputs.sumOf { max(0L, it.size) }
        wheel.label = listOf(Engine.TITLES.getValue(m), if (total > 0) humanSize(total) else "").filter { it.isNotEmpty() }.joinToString(" · ")
        hint.text = when (m) {
            "convert" -> "Tap a format. The new file goes to ${Downloads.WHERE}."
            "tools" -> "Tap a tool. Your original file isn't changed."
            "fit" -> "Pick the limit your form allows. You get a JPG just under it."
            else -> "Tap an action."
        }
    }

    private fun pick(petal: Petal) {
        if (busy) return
        val m = mode
        if (m == "actions" && petal.key == "share") return share(inputs.map { it.uri }, inputs.map { it.mime })
        if (m == "actions" && petal.key == "name") {
            val names = inputs.joinToString("\n") { it.name }
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("File name", names))
            toast(if (inputs.size == 1) "Copied the file name" else "Copied ${inputs.size} file names")
            return
        }
        if (m == "tools" && petal.key == "fit") return showMode("fit")
        if (m == "fit" && petal.key == "custom") return askCustomFit(petal)
        start(m, petal.key, petal)
    }

    /** Runs [key] in the background; [shown] is the petal that lights up meanwhile. */
    private fun start(m: String, key: String, shown: Petal) {
        busy = true
        wheel.setBusy(shown)
        hint.text = when (m) {
            "convert" -> "Converting to ${shown.label}…"
            "fit" -> "Fitting under ${key.substringBefore(':')} KB…"
            else -> "${shown.label}…"
        }
        val files = inputs
        worker.execute {
            val outcome = runCatching {
                Engine.run(this, m, key, files) { p -> main.post { wheel.progress = p.coerceIn(0, 100) } }
            }
            main.post {
                if (isDestroyed) return@post
                busy = false
                wheel.setBusy(null)
                outcome.onSuccess(::showResult).onFailure(::showError)
            }
        }
    }

    /** Custom Fit size: a KB limit, and the exact width and height if the form asks for them. */
    private fun askCustomFit(petal: Petal) {
        val dp = resources.displayMetrics.density
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        val kb = field("Size limit in KB, e.g. 50")
        val width = field("Width in px (optional)")
        val height = field("Height in px (optional)")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (22 * dp).toInt()
            setPadding(pad, (6 * dp).toInt(), pad, 0)
            addView(kb)
            addView(width)
            addView(height)
        }
        AlertDialog.Builder(this)
            .setTitle("Custom size")
            .setMessage("Fill in the width and height too if the form asks for exact dimensions.")
            .setView(box)
            .setPositiveButton("Fit") { _, _ ->
                val limit = kb.text.toString().toIntOrNull()
                if (limit == null || limit < 1) toast("Enter a size limit in KB.")
                else start("fit", "$limit:${width.text.toString().toIntOrNull() ?: 0}:${height.text.toString().toIntOrNull() ?: 0}", petal)
            }
            .setNegativeButton("Cancel", null)
            .show()
        kb.requestFocus()
    }

    private fun showResult(outcome: Outcome) {
        saved = outcome.saved
        resultTitle.text = "✓  ${outcome.title}"
        resultBody.text = when (saved.size) {
            0 -> "Nothing to save."
            1 -> "Saved ${saved[0].name}" + (outcome.detail?.let { " ($it)" } ?: "") + "\nin ${Downloads.WHERE}"
            else -> "Saved ${saved.size} files in ${Downloads.WHERE}"
        }
        openButton.visibility = if (saved.size == 1) View.VISIBLE else View.GONE
        shareButton.visibility = if (saved.isNotEmpty()) View.VISIBLE else View.GONE
        doneButton.text = "Done"
        showCard()
        if (Updater.choice(this) == null) askAboutUpdates()
    }

    private fun showError(error: Throwable) {
        saved = emptyList()
        resultTitle.text = "Bloom couldn't finish that"
        resultBody.text = when (error) {
            is OutOfMemoryError -> "This file is too big to handle on this phone."
            else -> (error.message ?: error.javaClass.simpleName).take(300)
        }
        openButton.visibility = View.GONE
        shareButton.visibility = View.GONE
        doneButton.text = "Back"
        showCard()
    }

    private fun showCard() {
        showMode(mode)
        result.visibility = View.VISIBLE
        result.translationY = 80 * resources.displayMetrics.density
        result.alpha = 0f
        result.animate().translationY(0f).alpha(1f).setDuration(260).start()
    }

    /** Done: back to the app that shared the files. Back: try something else. */
    private fun closeResult() {
        result.visibility = View.GONE
        if (saved.isNotEmpty() && fromShare) finish()
    }

    private fun leave() {
        if (busy) return
        if (fromShare) finish() else showHome()
    }

    private fun open(file: Saved) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(file.uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast("No app on this phone opens ${file.name.substringAfterLast('.').uppercase()} files.")
        }
    }

    private fun share(uris: List<Uri>, mimes: List<String>) {
        if (uris.isEmpty()) return
        val type = mimes.distinct().singleOrNull()
            ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = type
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, BloomActivity::class.java)))
        startActivity(chooser)
    }

    private fun loadThumb(input: Input) {
        thumb.setImageDrawable(null)
        thumbExt.text = input.ext.uppercase().take(4).ifEmpty { "FILE" }
        thumbExt.visibility = View.VISIBLE
        val px = (64 * resources.displayMetrics.density).toInt()
        worker.execute {
            val bitmap = runCatching { thumbnailFor(input, px) }.getOrNull()
            main.post {
                if (bitmap != null && !isDestroyed && inputs.firstOrNull() === input) {
                    thumb.setImageBitmap(bitmap)
                    thumbExt.visibility = View.GONE
                }
            }
        }
    }

    private fun thumbnailFor(input: Input, px: Int): Bitmap? = when {
        input.kind == Kind.IMAGE -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, input.uri)) { d, info, _ ->
            d.setTargetSampleSize(max(1, min(info.size.width, info.size.height) / px))
        }
        input.uri.scheme == "content" && (input.kind == Kind.VIDEO || input.kind == Kind.PDF) ->
            contentResolver.loadThumbnail(input.uri, Size(px, px), null)
        else -> null
    }

    // ---- updates ----

    private fun showUpdatesToggle() {
        updatesToggle.text = if (Updater.choice(this) == "on") "Update check: on · turn off" else "Update check: off · turn on"
    }

    private fun showNotice(title: String, body: String, yes: String?, no: String?, onYes: () -> Unit = {}, onNo: () -> Unit = {}) {
        noticeTitle.text = title
        noticeBody.text = body
        noticeButtons.visibility = if (yes == null && no == null) View.GONE else View.VISIBLE
        noticeYes.visibility = if (yes == null) View.GONE else View.VISIBLE
        noticeNo.visibility = if (no == null) View.GONE else View.VISIBLE
        noticeYes.text = yes
        noticeNo.text = no
        noticeYes.setOnClickListener { onYes() }
        noticeNo.setOnClickListener { onNo() }
        if (notice.visibility != View.VISIBLE) {
            notice.visibility = View.VISIBLE
            notice.translationY = -60 * resources.displayMetrics.density
            notice.alpha = 0f
            notice.animate().translationY(0f).alpha(1f).setDuration(240).start()
        }
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
    }

    /** Asked once. Until the user answers, Bloom never goes online. */
    private fun askAboutUpdates() {
        showNotice(
            "Get notified about new versions?",
            "Bloom would ask GitHub for the latest version number, once a day. Your files never leave your phone. You can change this on Bloom’s home screen.",
            "Yes, notify me", "No thanks",
            onYes = { setUpdates(true) },
            onNo = { setUpdates(false) })
    }

    private fun setUpdates(on: Boolean) {
        Updater.setChoice(this, on)
        showUpdatesToggle()
        hideNotice()
        if (on) checkForUpdate(quiet = false) else toast("Bloom won’t check for updates.")
    }

    /** [quiet]: say nothing unless there is an update (the daily check). */
    private fun checkForUpdate(quiet: Boolean) {
        val test = intent?.getStringExtra(EXTRA_TEST_VERSION)
        Executors.newSingleThreadExecutor().execute {
            val found = runCatching { Updater.check(this, Updater.currentVersion(this, test)) }
            main.post {
                if (isDestroyed) return@post
                val release = found.getOrNull()
                when {
                    release != null -> offerUpdate(release)
                    quiet -> Unit
                    found.isFailure -> toast("Couldn’t reach GitHub to check for updates.")
                    else -> toast("Bloom is up to date.")
                }
            }
        }
    }

    private fun offerUpdate(release: Release) {
        showNotice(
            "Bloom ${release.version} is available",
            release.notes.ifEmpty { "A new version of Bloom is ready to install." },
            "Update", "Later",
            onYes = { installUpdate(release) },
            onNo = { hideNotice() })
    }

    private fun installUpdate(release: Release) {
        if (updating) return
        updating = true
        showNotice("Downloading Bloom ${release.version}…", "0%", null, null)
        Executors.newSingleThreadExecutor().execute {
            val apk = runCatching {
                Updater.download(this, release) { p -> main.post { noticeBody.text = "$p%" } }
            }
            main.post {
                updating = false
                if (isDestroyed) return@post
                apk.onSuccess {
                    hideNotice()
                    try {
                        startActivity(Updater.installIntent(this, it))
                    } catch (e: ActivityNotFoundException) {
                        toast("This phone has no installer for APK files.")
                    }
                }.onFailure {
                    showNotice("Couldn’t download the update", "Check your connection and try again.",
                        "Try again", "Later", onYes = { installUpdate(release) }, onNo = { hideNotice() })
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val PICK_FILES = 1
        const val EXTRA_TEST_VERSION = "io.github.omjasoliya.bloom.TEST_VERSION"   // debug builds only
    }
}
