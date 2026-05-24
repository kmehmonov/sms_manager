package com.smsmanager.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smsmanager.app.databinding.ActivityMainBinding
import com.smsmanager.app.databinding.DialogCsvPreviewBinding

/**
 * Asosiy Activity.
 *
 * Vazifalar:
 * - SMS ruxsatini boshqarish
 * - CSV fayl tanlash va preview ko'rsatish
 * - Delay sozlamalarini o'qish
 * - ViewModel bilan bog'liq UI yangilashlari
 * - Log elementlariga bosish — tafsilot dialog
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SmsViewModel by viewModels()
    private lateinit var logAdapter: LogAdapter

    private var contacts: List<SmsContact> = emptyList()
    private var selectedUri: Uri? = null

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

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    // ─── UI sozlash ────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        logAdapter = LogAdapter { entry ->
            // Log elementiga bosish — to'liq tafsilot dialogi
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
        // Statistika kartalari
        binding.tvStatSent.text = state.sent.toString()
        binding.tvStatFailed.text = state.failed.toString()
        binding.tvStatTotal.text = state.total.toString()

        if (state.total > 0) {
            val done = state.sent + state.failed
            val percent = (done * 100 / state.total)

            binding.tvProgress.text = "$done / ${state.total} yuborildi"
            binding.tvProgressPercent.text = "$percent%"
            binding.progressBar.max = state.total
            binding.progressBar.progress = done
            binding.tvCurrentStatus.text = state.currentMessage
        } else {
            binding.tvProgress.text = getString(R.string.progress_idle)
            binding.tvProgressPercent.text = "0%"
            binding.progressBar.progress = 0
            binding.tvCurrentStatus.text = ""
        }
    }

    private fun updateButtonStates(isRunning: Boolean) {
        binding.btnStart.isEnabled = !isRunning && contacts.isNotEmpty()
        binding.btnStop.isEnabled = isRunning
        binding.btnSelectFile.isEnabled = !isRunning
        binding.checkSkipHeader.isEnabled = !isRunning
        binding.etMinDelay.isEnabled = !isRunning
        binding.etMaxDelay.isEnabled = !isRunning
    }

    // ─── Fayl tanlash ──────────────────────────────────────────────────────────

    private fun onFileSelected(uri: Uri) {
        selectedUri = uri
        try {
            val fileName = getFileName(uri)
            val skipHeader = binding.checkSkipHeader.isChecked
            contacts = CsvParser.parse(this, uri, skipHeader)

            if (contacts.isEmpty()) {
                showToast(getString(R.string.error_empty_file))
                showNoFileState()
                binding.btnStart.isEnabled = false
            } else {
                // Fayl info ko'rsatamiz
                binding.tvFileName.text = fileName
                binding.tvContactCount.text = "${contacts.size} ta raqam topildi"
                binding.layoutFileInfo.visibility = View.VISIBLE
                binding.layoutNoFile.visibility = View.GONE
                binding.btnStart.isEnabled = true
                showToast("${contacts.size} ta kontakt yuklandi ✓")
            }
        } catch (e: Exception) {
            showToast("Fayl o'qishda xato: ${e.message}")
            showNoFileState()
        }
    }

    private fun showNoFileState() {
        binding.layoutFileInfo.visibility = View.GONE
        binding.layoutNoFile.visibility = View.VISIBLE
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

    /**
     * CSV tarkibini dialog oynada ko'rsatadi.
     * RecyclerView bilan skrollable ro'yxat.
     */
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

        // Delay qiymatlarini o'qiymiz
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

        // Millisekundga o'tkazamiz
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

    /**
     * Log elementiga bosilganda to'liq tafsilotni ko'rsatadi.
     * Uzun xabar bu yerda to'liq skrollable ko'rinishda chiqadi.
     */
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
