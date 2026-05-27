package com.smsmanager.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smsmanager.app.databinding.ActivityMainBinding
import com.smsmanager.app.databinding.DialogColumnMappingBinding
import com.smsmanager.app.databinding.DialogCsvPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Asosiy Activity.
 *
 * Vazifalar:
 * - SMS ruxsatini boshqarish
 * - CSV fayl tanlash va preview ko'rsatish
 * - Delay sozlamalarini o'qish
 * - ViewModel bilan bog'liq UI yangilashlari
 * - Log elementlariga bosish — tafsilot dialog
 * - In-app yangilanish: GitHub Release tekshirish → yuklab olish → o'rnatish
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SmsViewModel by viewModels()
    private lateinit var logAdapter: LogAdapter

    private var contacts: List<SmsContact> = emptyList()
    private var selectedUri: Uri? = null

    // Yangilanish yuklab olish uchun DownloadManager ID
    private var downloadId: Long = -1L
    private var pendingDownloadUrl: String? = null

    // ─── Fayl picker launcher ──────────────────────────────────────────────────
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onFileSelected(it) } ?: showToast("Fayl tanlanmadi")
    }

    // ─── SMS ruxsat launcher ───────────────────────────────────────────────────
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSendingIfReady()
        else showPermissionDeniedDialog()
    }

    // ─── APK o'rnatish ruxsati launcher (Android 8.0+) ────────────────────────
    private val requestInstallPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Sozlamalardan qaytgach — agar ruxsat berilgan bo'lsa, yuklab olishni boshlaymiz
        val url = pendingDownloadUrl
        if (url != null && canInstallPackages()) {
            pendingDownloadUrl = null
            startApkDownload(url)
        }
    }

    // ─── APK yuklab olish tugashi uchun BroadcastReceiver ─────────────────────
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) {
                onDownloadCompleted(id)
            }
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        // Ilova ochilganda — yangilanishni tekshiramiz
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        // DownloadManager broadcast'ini ro'yxatga olamiz
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
    }

    // ─── UI sozlash ────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        logAdapter = LogAdapter { entry ->
            showLogDetailDialog(entry)
        }
        binding.recyclerLog.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupClickListeners() {
        // Fayl tanlash
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // Fayl info kartasiga bosish — CSV preview
        binding.layoutFileInfo.setOnClickListener {
            selectedUri?.let { showCsvPreviewDialog() }
        }
        binding.btnPreviewCsv.setOnClickListener {
            selectedUri?.let { showCsvPreviewDialog() }
        }

        // Yuborishni boshlash
        binding.btnStart.setOnClickListener { onStartClicked() }

        // To'xtatish
        binding.btnStop.setOnClickListener { onStopClicked() }

        // Log tozalash
        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }

        // Yangilash tugmasi — banner ko'rinsa ishlatiladi
        binding.btnUpdate.setOnClickListener {
            val url = binding.btnUpdate.tag as? String
            if (url != null) downloadAndInstall(url)
        }
    }

    private fun observeViewModel() {
        // Log ro'yxati
        viewModel.logEntries.observe(this) { entries ->
            logAdapter.submitList(entries.toList()) {
                if (entries.isNotEmpty())
                    binding.recyclerLog.scrollToPosition(entries.size - 1)
            }
        }

        // Yuborish holati
        viewModel.sendingState.observe(this) { state ->
            updateProgressUI(state)
            updateButtonStates(state.isRunning)
        }

        // Xato xabarlari
        viewModel.errorEvent.observe(this) { error ->
            if (error != null) {
                showToast(error)
                viewModel.onErrorShown()
            }
        }
    }

    // ─── Progress UI yangilash ─────────────────────────────────────────────────

    private fun updateProgressUI(state: SendingState) {
        binding.tvStatSent.text   = state.sent.toString()
        binding.tvStatFailed.text = state.failed.toString()
        binding.tvStatTotal.text  = state.total.toString()

        if (state.total > 0) {
            val done    = state.sent + state.failed
            val percent = (done * 100 / state.total)

            binding.tvProgress.text        = "$done / ${state.total} yuborildi"
            binding.tvProgressPercent.text  = "$percent%"
            binding.progressBar.max        = state.total
            binding.progressBar.progress   = done
            binding.tvCurrentStatus.text   = state.currentMessage
        } else {
            binding.tvProgress.text        = getString(R.string.progress_idle)
            binding.tvProgressPercent.text  = "0%"
            binding.progressBar.progress   = 0
            binding.tvCurrentStatus.text   = ""
        }
    }

    private fun updateButtonStates(isRunning: Boolean) {
        binding.btnStart.isEnabled       = !isRunning && contacts.isNotEmpty()
        binding.btnStop.isEnabled        = isRunning
        binding.btnSelectFile.isEnabled  = !isRunning
        binding.checkSkipHeader.isEnabled = !isRunning
        binding.etMinDelay.isEnabled     = !isRunning
        binding.etMaxDelay.isEnabled     = !isRunning
    }

    // ─── Fayl tanlash ──────────────────────────────────────────────────────────

    private fun onFileSelected(uri: Uri) {
        selectedUri = uri
        lifecycleScope.launch {
            try {
                val rawRows = withContext(Dispatchers.IO) {
                    CsvParser.readRawRows(this@MainActivity, uri, maxRows = 5)
                }
                if (rawRows.isEmpty()) {
                    showToast(getString(R.string.error_empty_file))
                    showNoFileState()
                    return@launch
                }
                showColumnMappingDialog(uri, rawRows)
            } catch (e: Exception) {
                showToast("Fayl o'qishda xato: ${e.message}")
                showNoFileState()
            }
        }
    }

    /**
     * CSV ustunlarini tanlash uchun mapping dialogi.
     * rawRows — CSV ning birinchi bir nechta qatorlari (xom holda).
     */
    private fun showColumnMappingDialog(uri: Uri, rawRows: List<List<String>>) {
        val skipHeader = binding.checkSkipHeader.isChecked
        val columnCount = rawRows.maxOf { it.size }

        // Ustun nomlarini quramiz
        val headerRow = if (skipHeader && rawRows.isNotEmpty()) rawRows[0] else null
        val columnLabels = (0 until columnCount).map { i ->
            val name = headerRow?.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) "Ustun ${i + 1}: $name"
            else {
                val sample = rawRows[0].getOrNull(i)?.trim()?.take(15)?.takeIf { it.isNotEmpty() }
                if (sample != null) "Ustun ${i + 1} ($sample…)" else "Ustun ${i + 1}"
            }
        }

        // Preview matnini quramiz — birinchi 4 qator
        val previewText = buildString {
            val previewRows = rawRows.take(4)
            previewRows.forEachIndexed { rowIdx, row ->
                if (skipHeader && rowIdx == 0) append("[Sarlavha] ")
                row.forEachIndexed { colIdx, cell ->
                    append("[${colIdx + 1}] ")
                    append(cell.take(18))
                    if (colIdx < row.size - 1) append("  ")
                }
                appendLine()
            }
        }.trimEnd()

        val dialogBinding = DialogColumnMappingBinding.inflate(layoutInflater)
        dialogBinding.tvMappingPreview.text = previewText

        // Dropdown adapter
        val dropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, columnLabels)
        val spinnerPhone   = dialogBinding.spinnerPhoneColumn
        val spinnerMessage = dialogBinding.spinnerMessageColumn

        spinnerPhone.setAdapter(dropdownAdapter)
        spinnerMessage.setAdapter(dropdownAdapter)

        // Standart tanlov: 0 — telefon, 1 — xabar (agar 2+ ustun bo'lsa)
        var phoneColIndex   = 0
        var messageColIndex = if (columnCount > 1) 1 else 0

        spinnerPhone.setText(columnLabels.getOrNull(phoneColIndex) ?: "", false)
        spinnerMessage.setText(columnLabels.getOrNull(messageColIndex) ?: "", false)

        spinnerPhone.setOnItemClickListener { _, _, position, _ ->
            phoneColIndex = position
            dialogBinding.tilPhoneColumn.error = null
        }
        spinnerMessage.setOnItemClickListener { _, _, position, _ ->
            messageColIndex = position
            dialogBinding.tilPhoneColumn.error = null
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Ustun moslash (Mapping)")
            .setView(dialogBinding.root)
            .setPositiveButton("Tasdiqlash", null)
            .setNegativeButton("Bekor", null)
            .create()

        dialog.show()

        // Positive tugma — validatsiya bilan
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (phoneColIndex == messageColIndex) {
                dialogBinding.tilPhoneColumn.error = "Telefon va xabar ustunlari farqli bo'lishi kerak"
                return@setOnClickListener
            }
            dialogBinding.tilPhoneColumn.error = null
            dialog.dismiss()
            applyMappingAndLoad(uri, skipHeader, phoneColIndex, messageColIndex)
        }
    }

    /**
     * Mapping tasdiqlangandan so'ng CSV ni parse qilib kontaktlarni yuklaydi.
     */
    private fun applyMappingAndLoad(
        uri: Uri,
        skipHeader: Boolean,
        phoneColIndex: Int,
        messageColIndex: Int
    ) {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    CsvParser.parseWithMapping(this@MainActivity, uri, skipHeader, phoneColIndex, messageColIndex)
                }
                val fileName = getFileName(uri)
                if (loaded.isEmpty()) {
                    showToast(getString(R.string.error_empty_file))
                    showNoFileState()
                } else {
                    contacts = loaded
                    binding.tvFileName.text     = fileName
                    binding.tvContactCount.text = "${contacts.size} ta raqam topildi"
                    binding.layoutFileInfo.visibility = View.VISIBLE
                    binding.layoutNoFile.visibility   = View.GONE
                    binding.btnStart.isEnabled = true
                    showToast("${contacts.size} ta kontakt yuklandi ✓")
                }
            } catch (e: Exception) {
                showToast("Fayl o'qishda xato: ${e.message}")
                showNoFileState()
            }
        }
    }

    private fun showNoFileState() {
        binding.layoutFileInfo.visibility = View.GONE
        binding.layoutNoFile.visibility   = View.VISIBLE
        contacts = emptyList()
        binding.btnStart.isEnabled = false
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) return cursor.getString(idx) ?: "fayl.csv"
            }
        }
        return uri.lastPathSegment ?: "fayl.csv"
    }

    // ─── CSV Preview dialogi ───────────────────────────────────────────────────

    private fun showCsvPreviewDialog() {
        if (contacts.isEmpty()) {
            showToast("Ko'rsatadigan ma'lumot yo'q")
            return
        }

        val dialogBinding = DialogCsvPreviewBinding.inflate(layoutInflater)
        val adapter = CsvPreviewAdapter(contacts)

        dialogBinding.recyclerCsvPreview.apply {
            this.adapter = adapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        MaterialAlertDialogBuilder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
            .setTitle("${getString(R.string.csv_preview_title)} — ${contacts.size} ta")
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    // ─── SMS yuborish ─────────────────────────────────────────────────────────

    private fun onStartClicked() {
        if (contacts.isEmpty()) { showToast(getString(R.string.error_no_file)); return }
        checkSmsPermissionAndStart()
    }

    private fun onStopClicked() {
        MaterialAlertDialogBuilder(this)
            .setTitle("To'xtatish")
            .setMessage("Joriy SMS yuborilib bo'lgach jarayon to'xtatiladi. Davom ettirasizmi?")
            .setPositiveButton("Ha, to'xtat") { _, _ ->
                viewModel.stopSending()
                showToast("To'xtatilmoqda…")
            }
            .setNegativeButton("Yo'q", null)
            .show()
    }

    private fun checkSmsPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED -> startSendingIfReady()

            shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("SMS Ruxsati kerak")
                    .setMessage(getString(R.string.permission_rationale))
                    .setPositiveButton("Ruxsat berish") { _, _ ->
                        requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                    .setNegativeButton("Bekor", null)
                    .show()

            else -> requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    /**
     * Delay qiymatlarini tekshirib, yuborishni boshlaydi.
     */
    private fun startSendingIfReady() {
        if (contacts.isEmpty()) { showToast(getString(R.string.error_no_file)); return }

        val minSec = binding.etMinDelay.text.toString().toLongOrNull() ?: 4L
        val maxSec = binding.etMaxDelay.text.toString().toLongOrNull() ?: 6L

        if (minSec < 1 || maxSec < minSec) {
            showToast(getString(R.string.error_delay_invalid))
            binding.tilMinDelay.error = if (minSec < 1) "Min ≥ 1" else null
            binding.tilMaxDelay.error = if (maxSec < minSec) "Max ≥ Min" else null
            return
        }
        binding.tilMinDelay.error = null
        binding.tilMaxDelay.error = null

        val minMs = minSec * 1000L
        val maxMs = maxSec * 1000L

        MaterialAlertDialogBuilder(this)
            .setTitle("Yuborishni boshlash")
            .setMessage(
                "${contacts.size} ta raqamga SMS yuboriladi.\n" +
                "Kutish: ${minSec}–${maxSec} sekund\n\n" +
                "Davom etasizmi?"
            )
            .setPositiveButton("Ha, boshlash") { _, _ ->
                viewModel.startSending(contacts, minMs, maxMs)
            }
            .setNegativeButton("Bekor", null)
            .show()
    }

    // ─── Log tafsilot dialogi ──────────────────────────────────────────────────

    private fun showLogDetailDialog(entry: SmsLogEntry) {
        val statusText = when (entry.status) {
            SmsStatus.PENDING -> "⏳ Navbatda"
            SmsStatus.SENDING -> "📤 Yuborilmoqda"
            SmsStatus.SENT    -> "✅ Muvaffaqiyatli yuborildi"
            SmsStatus.FAILED  -> "❌ Xato: ${entry.errorMessage ?: "Noma'lum"}"
        }

        val message = buildString {
            appendLine("📱 Telefon:  ${entry.phoneNumber}")
            appendLine()
            appendLine("📝 Xabar:")
            appendLine(entry.message)
            appendLine()
            appendLine("📊 Holat:  $statusText")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("#${entry.index} — ${entry.phoneNumber}")
            .setMessage(message)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    // ─── Yangilanish logikasi ──────────────────────────────────────────────────

    /**
     * Ilova ochilganda GitHub'dan yangi versiyani tekshiradi.
     * Fon rejimida ishlaydi — UI'ni blokirovka qilmaydi.
     */
    private fun checkForUpdates() {
        lifecycleScope.launch {
            val currentCode = getInstalledVersionCode()
            val updateInfo  = UpdateChecker.checkForUpdate(currentCode)
            if (updateInfo != null) {
                showUpdateBanner(updateInfo)
            }
        }
    }

    /** O'rnatilgan APK ning versionCode raqamini qaytaradi. */
    @Suppress("DEPRECATION")
    private fun getInstalledVersionCode(): Int {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 0 }
    }

    /** Yangi versiya topilganda yuqori bannerda ko'rsatadi. */
    private fun showUpdateBanner(updateInfo: UpdateInfo) {
        binding.tvUpdateTitle.text = "🆕 ${updateInfo.releaseTitle} — yangilash mavjud"
        binding.btnUpdate.tag      = updateInfo.downloadUrl   // URLni saqlaymiz
        binding.layoutUpdateBanner.visibility = View.VISIBLE
    }

    /**
     * Yuklab olishdan oldin REQUEST_INSTALL_PACKAGES ruxsatini tekshiradi.
     * Android 8.0 (Oreo)+ da talab qilinadi.
     */
    private fun downloadAndInstall(url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            pendingDownloadUrl = url
            MaterialAlertDialogBuilder(this)
                .setTitle("O'rnatish ruxsati")
                .setMessage("Yangilanishni o'rnatish uchun noma'lum manbalardan o'rnatishga ruxsat bering.\n\nSozlamalar → Maxsus dastur kirishi → Noma'lum manbalar")
                .setPositiveButton("Sozlamalarga o'tish") { _, _ ->
                    requestInstallPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Bekor", null)
                .show()
            return
        }
        startApkDownload(url)
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
    }

    /**
     * DownloadManager orqali APK yuklab olishni boshlaydi.
     */
    private fun startApkDownload(url: String) {
        // Eski faylni tozalaymiz
        val outputFile = File(getExternalFilesDir(null), "sms-manager-update.apk")
        if (outputFile.exists()) outputFile.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("SMS Manager yangilanishi")
            setDescription("Yangi versiya yuklab olinmoqda…")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationUri(Uri.fromFile(outputFile))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        // Tugma holatini yangilaymiz
        binding.btnUpdate.isEnabled = false
        binding.btnUpdate.text      = "Yuklanmoqda…"
        showToast("Yangilanish yuklab olinmoqda…")
    }

    /**
     * DownloadManager yuklab olishni tugatganda chaqiriladi.
     */
    private fun onDownloadCompleted(id: Long) {
        val dm    = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = dm.query(query)

        if (cursor.moveToFirst()) {
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val status    = cursor.getInt(statusCol)

            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val outputFile = File(getExternalFilesDir(null), "sms-manager-update.apk")
                    if (outputFile.exists()) {
                        installApk(outputFile)
                    } else {
                        showToast("Fayl topilmadi")
                        resetUpdateButton()
                    }
                }
                DownloadManager.STATUS_FAILED -> {
                    showToast("Yuklab olishda xato yuz berdi")
                    resetUpdateButton()
                }
            }
        }
        cursor.close()
    }

    /**
     * FileProvider orqali xavfsiz URI yaratib, APK o'rnatishni boshlaydi.
     */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun resetUpdateButton() {
        binding.btnUpdate.isEnabled = true
        binding.btnUpdate.text      = "Qayta urinish"
    }

    // ─── Ruxsat rad etilganda ─────────────────────────────────────────────────

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("SMS Ruxsati berilmadi")
            .setMessage(
                "SMS yuborish uchun ruxsat kerak.\n\n" +
                "Sozlamalar → Ilovalar → SMS Manager → Ruxsatlar → SMS → Ruxsat berish"
            )
            .setPositiveButton("Tushundim", null)
            .show()
    }

    // ─── Yordamchi ────────────────────────────────────────────────────────────

    private fun showToast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
