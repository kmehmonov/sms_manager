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
import android.telephony.SubscriptionManager
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.Spinner
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SmsViewModel by viewModels()
    private lateinit var logAdapter: LogAdapter

    private var contacts: List<SmsContact> = emptyList()
    private var selectedUri: Uri? = null

    // SIM karta
    private var simSubscriptionIds: List<Int?> = listOf(null)
    private var selectedSimSubscriptionId: Int? = null

    // Statistika dialogi uchun — oldingi holat trakkeri
    private var wasSendingRunning = false

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
        detectSimCards()

        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
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
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.layoutFileInfo.setOnClickListener {
            selectedUri?.let { showCsvPreviewDialog() }
        }
        binding.btnPreviewCsv.setOnClickListener {
            selectedUri?.let { showCsvPreviewDialog() }
        }

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
        binding.btnClearLog.setOnClickListener { viewModel.clearLog() }

        binding.btnUpdate.setOnClickListener {
            val url = binding.btnUpdate.tag as? String
            if (url != null) downloadAndInstall(url)
        }

        // SIM spinner
        binding.spinnerSim.setOnItemClickListener { _, _, position, _ ->
            selectedSimSubscriptionId = simSubscriptionIds.getOrNull(position)
        }
    }

    private fun observeViewModel() {
        viewModel.logEntries.observe(this) { entries ->
            logAdapter.submitList(entries.toList()) {
                if (entries.isNotEmpty())
                    binding.recyclerLog.scrollToPosition(entries.size - 1)
            }
        }

        viewModel.sendingState.observe(this) { state ->
            updateProgressUI(state)
            updateButtonStates(state.isRunning)

            // Yuborish tugaganda (foydalanuvchi to'xtatmagan bo'lsa) statistika dialogi
            if (wasSendingRunning && !state.isRunning && state.total > 0 && !state.stoppedByUser) {
                showSendingStatsDialog(state)
            }
            wasSendingRunning = state.isRunning
        }

        viewModel.errorEvent.observe(this) { error ->
            if (error != null) {
                showToast(error)
                viewModel.onErrorShown()
            }
        }
    }

    // ─── SIM karta aniqlash ────────────────────────────────────────────────────

    private fun detectSimCards() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            binding.layoutSimSelector.visibility = View.GONE
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            binding.layoutSimSelector.visibility = View.GONE
            return
        }

        val subscriptionManager = getSystemService(SubscriptionManager::class.java) ?: run {
            binding.layoutSimSelector.visibility = View.GONE
            return
        }

        val sims = try {
            subscriptionManager.activeSubscriptionInfoList
        } catch (_: Exception) {
            null
        }

        if (sims == null || sims.size <= 1) {
            binding.layoutSimSelector.visibility = View.GONE
            simSubscriptionIds = listOf(null)
            return
        }

        // 2+ SIM karta topildi — spinnerini ko'rsatamiz
        binding.layoutSimSelector.visibility = View.VISIBLE

        simSubscriptionIds = listOf(null) + sims.map { it.subscriptionId }
        val labels = listOf("Standart (avtomatik)") + sims.map { sim ->
            val name = sim.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: "SIM ${sim.simSlotIndex + 1}"
            "SIM ${sim.simSlotIndex + 1} ($name)"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.spinnerSim.setAdapter(adapter)
        binding.spinnerSim.setText(labels[0], false)
        selectedSimSubscriptionId = null
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
        binding.btnStart.isEnabled        = !isRunning && contacts.isNotEmpty()
        binding.btnStop.isEnabled         = isRunning
        binding.btnSelectFile.isEnabled   = !isRunning
        binding.checkSkipHeader.isEnabled = !isRunning
        binding.etMinDelay.isEnabled      = !isRunning
        binding.etMaxDelay.isEnabled      = !isRunning
        binding.spinnerSim.isEnabled      = !isRunning
    }

    // ─── Yuborish statistikasi dialogi ────────────────────────────────────────

    private fun showSendingStatsDialog(state: SendingState) {
        val message = "Jami:       ${state.total}\n" +
                      "Yuborildi:  ✅ ${state.sent}\n" +
                      "Xato:       ❌ ${state.failed}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Yuborish yakunlandi!")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
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
     */
    private fun showColumnMappingDialog(uri: Uri, rawRows: List<List<String>>) {
        val skipHeader = binding.checkSkipHeader.isChecked
        val columnCount = rawRows.maxOf { it.size }

        val headerRow = if (skipHeader && rawRows.isNotEmpty()) rawRows[0] else null
        val columnLabels = (0 until columnCount).map { i ->
            val name = headerRow?.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) "Ustun ${i + 1}: $name"
            else {
                val sample = rawRows[0].getOrNull(i)?.trim()?.take(15)?.takeIf { it.isNotEmpty() }
                if (sample != null) "Ustun ${i + 1} ($sample…)" else "Ustun ${i + 1}"
            }
        }

        val previewText = buildString {
            rawRows.take(4).forEachIndexed { rowIdx, row ->
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

        // Phone dropdown
        val dropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, columnLabels)
        dialogBinding.spinnerPhoneColumn.setAdapter(dropdownAdapter)

        var phoneColIndex = 0
        var messageColIndex = if (columnCount > 1) 1 else 0

        dialogBinding.spinnerPhoneColumn.setText(columnLabels.getOrNull(phoneColIndex) ?: "", false)
        dialogBinding.spinnerPhoneColumn.setOnItemClickListener { _, _, position, _ ->
            phoneColIndex = position
            dialogBinding.tilPhoneColumn.error = null
        }

        // Message dropdown (CSV rejimi)
        dialogBinding.spinnerMessageColumn.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, columnLabels)
        )
        dialogBinding.spinnerMessageColumn.setText(columnLabels.getOrNull(messageColIndex) ?: "", false)
        dialogBinding.spinnerMessageColumn.setOnItemClickListener { _, _, position, _ ->
            messageColIndex = position
        }

        // Template rejimi
        var isTemplateMode = false
        val varMappingIndices = mutableMapOf<String, Int>()

        dialogBinding.rgMessageSource.setOnCheckedChangeListener { _, checkedId ->
            isTemplateMode = (checkedId == R.id.rbTemplate)
            dialogBinding.tilMessageColumn.visibility = if (isTemplateMode) View.GONE else View.VISIBLE
            dialogBinding.layoutTemplateSection.visibility = if (isTemplateMode) View.VISIBLE else View.GONE
        }

        dialogBinding.etTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val template = s?.toString() ?: ""
                val varNames = CsvParser.extractTemplateVars(template)
                dialogBinding.tvVarMappingLabel.visibility =
                    if (varNames.isNotEmpty()) View.VISIBLE else View.GONE
                rebuildVarMappings(dialogBinding.layoutVarMappings, varNames, columnLabels, varMappingIndices)
            }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Ustun moslash (Mapping)")
            .setView(dialogBinding.root)
            .setPositiveButton("Tasdiqlash", null)
            .setNegativeButton("Bekor", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (isTemplateMode) {
                val template = dialogBinding.etTemplate.text?.toString()?.trim() ?: ""
                if (template.isEmpty()) {
                    dialogBinding.tilTemplate.error = "Shablon bo'sh bo'lishi mumkin emas"
                    return@setOnClickListener
                }
                dialogBinding.tilTemplate.error = null
                dialog.dismiss()
                applyTemplateAndLoad(uri, skipHeader, phoneColIndex, template, varMappingIndices.toMap())
            } else {
                if (phoneColIndex == messageColIndex) {
                    dialogBinding.tilPhoneColumn.error = "Telefon va xabar ustunlari farqli bo'lishi kerak"
                    return@setOnClickListener
                }
                dialogBinding.tilPhoneColumn.error = null
                dialog.dismiss()
                applyMappingAndLoad(uri, skipHeader, phoneColIndex, messageColIndex)
            }
        }
    }

    /**
     * Shablon o'zgaruvchilari uchun ustun moslash spinnerlarini qayta quradi.
     */
    private fun rebuildVarMappings(
        container: LinearLayout,
        varNames: List<String>,
        columnLabels: List<String>,
        varMappingIndices: MutableMap<String, Int>
    ) {
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val pad8 = (8 * density).toInt()

        varNames.forEach { varName ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { lp -> lp.setMargins(0, 0, 0, pad8) }
            }

            val label = android.widget.TextView(this).apply {
                text = "{{$varName}} →"
                textSize = 13f
                setPadding(0, 0, pad8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    columnLabels
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val savedIndex = varMappingIndices[varName] ?: 0
                setSelection(savedIndex.coerceIn(0, columnLabels.size - 1))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) { varMappingIndices[varName] = position }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            if (!varMappingIndices.containsKey(varName)) varMappingIndices[varName] = 0

            row.addView(label)
            row.addView(spinner)
            container.addView(row)
        }
    }

    /**
     * CSV rejimida mapping tasdiqlangandan so'ng CSV ni yuklaydi.
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

    /**
     * Shablon rejimida tasdiqlangandan so'ng CSV ni yuklaydi.
     */
    private fun applyTemplateAndLoad(
        uri: Uri,
        skipHeader: Boolean,
        phoneColIndex: Int,
        template: String,
        varMappings: Map<String, Int>
    ) {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    CsvParser.parseWithTemplate(
                        this@MainActivity, uri, skipHeader, phoneColIndex, template, varMappings
                    )
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
                viewModel.startSending(contacts, minMs, maxMs, selectedSimSubscriptionId)
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

    private fun checkForUpdates() {
        lifecycleScope.launch {
            val currentCode = getInstalledVersionCode()
            val updateInfo  = UpdateChecker.checkForUpdate(currentCode)
            if (updateInfo != null) {
                showUpdateBanner(updateInfo)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledVersionCode(): Int {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 0 }
    }

    private fun showUpdateBanner(updateInfo: UpdateInfo) {
        binding.tvUpdateTitle.text = "🆕 ${updateInfo.releaseTitle} — yangilash mavjud"
        binding.btnUpdate.tag      = updateInfo.downloadUrl
        binding.layoutUpdateBanner.visibility = View.VISIBLE
    }

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

    private fun startApkDownload(url: String) {
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

        binding.btnUpdate.isEnabled = false
        binding.btnUpdate.text      = "Yuklanmoqda…"
        showToast("Yangilanish yuklab olinmoqda…")
    }

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
