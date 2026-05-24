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
import com.smsmanager.app.databinding.ActivityMainBinding

/**
 * Ilovaning asosiy Activity sinfi.
 *
 * Bu sinf quyidagi vazifalarni bajaradi:
 * 1. SMS ruxsatini so'rash va boshqarish
 * 2. CSV fayl tanlash (file picker)
 * 3. ViewModel bilan bog'liq UI yangilashlari
 * 4. RecyclerView log ro'yxatini boshqarish
 */
class MainActivity : AppCompatActivity() {

    // ─── ViewBinding — XML elementlariga xavfsiz kirish ────────────────────
    private lateinit var binding: ActivityMainBinding

    // ─── ViewModel — biznes mantiq va holat ─────────────────────────────────
    private val viewModel: SmsViewModel by viewModels()

    // ─── RecyclerView adapteri — log ro'yxati ───────────────────────────────
    private lateinit var logAdapter: LogAdapter

    // ─── O'qilgan kontaktlar ro'yxati ───────────────────────────────────────
    private var contacts: List<SmsContact> = emptyList()

    // ─── Fayl tanlash uchun ActivityResult launcher ─────────────────────────
    /**
     * File picker — foydalanuvchi telefon xotirasidan CSV fayl tanlaganda ishga tushadi.
     * ActivityResultContracts.GetContent() — zamonaviy API, eski startActivityForResult o'rniga.
     */
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Fayl tanlandi — o'qishni boshlaymiz
            onFileSelected(uri)
        } else {
            // Foydalanuvchi fayl tanlamay orqaga qaytdi
            showToast("Fayl tanlanmadi")
        }
    }

    // ─── SMS ruxsatini so'rash uchun launcher ───────────────────────────────
    /**
     * Runtime ruxsat so'rash — foydalanuvchi javob berganda bu callback ishga tushadi.
     */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Ruxsat berildi — yuborishni boshlaymiz
            showToast("SMS ruxsati berildi ✓")
            startSendingIfReady()
        } else {
            // Ruxsat rad etildi — foydalanuvchiga tushuntirish beramiz
            showPermissionDeniedDialog()
        }
    }

    // ─── Activity lifecycle ──────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding — XML layoutni yuklash
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar'ni o'rnatamiz
        setSupportActionBar(binding.toolbar)

        // RecyclerView'ni sozlaymiz
        setupRecyclerView()

        // Tugmalar uchun click listener'larni o'rnatamiz
        setupClickListeners()

        // ViewModel'ni kuzatishni boshlaymiz
        observeViewModel()
    }

    // ─── UI sozlash ─────────────────────────────────────────────────────────

    /**
     * RecyclerView'ni sozlaydi — log ro'yxati uchun.
     */
    private fun setupRecyclerView() {
        logAdapter = LogAdapter()
        binding.recyclerLog.apply {
            adapter = logAdapter
            // LinearLayoutManager — elementlarni pastdan yuqoriga ko'rsatish uchun REVERSE
            layoutManager = LinearLayoutManager(this@MainActivity).also {
                it.stackFromEnd = true  // Yangi elementlar pastdan ko'rinadi
                it.reverseLayout = false
            }
        }
    }

    /**
     * Barcha tugmalar uchun click listener'larni o'rnatadi.
     */
    private fun setupClickListeners() {
        // CSV fayl tanlash tugmasi
        binding.btnSelectFile.setOnClickListener {
            // File picker'ni ochamiz — faqat CSV va text fayllar ko'rinadi
            filePickerLauncher.launch("*/*")
        }

        // Yuborishni boshlash tugmasi
        binding.btnStart.setOnClickListener {
            onStartClicked()
        }

        // To'xtatish tugmasi
        binding.btnStop.setOnClickListener {
            onStopClicked()
        }

        // Log'ni tozalash tugmasi
        binding.btnClearLog.setOnClickListener {
            viewModel.clearLog()
        }
    }

    /**
     * ViewModel LiveData'larini kuzatadi va UI'ni yangilaydi.
     */
    private fun observeViewModel() {
        // Log ro'yxatini kuzatish
        viewModel.logEntries.observe(this) { entries ->
            logAdapter.submitList(entries.toList()) {
                // Ro'yxat yangilangandan so'ng eng pastga skroll qilamiz
                if (entries.isNotEmpty()) {
                    binding.recyclerLog.scrollToPosition(entries.size - 1)
                }
            }
        }

        // Yuborish holati o'zgarishlarini kuzatish
        viewModel.sendingState.observe(this) { state ->
            updateProgressUI(state)
            updateButtonStates(state.isRunning)
        }

        // Xato xabarlarini kuzatish
        viewModel.errorEvent.observe(this) { error ->
            if (error != null) {
                showToast(error)
                viewModel.onErrorShown()
            }
        }
    }

    // ─── UI yangilash ────────────────────────────────────────────────────────

    /**
     * Progress blokini yangilaydi.
     */
    private fun updateProgressUI(state: SendingState) {
        if (state.total > 0) {
            // Progress matnini yangilaymiz: "45 / 120 yuborildi"
            binding.tvProgress.text = "${state.sent + state.failed} / ${state.total} yuborildi"

            // Progress bar'ni yangilaymiz
            binding.progressBar.max = state.total
            binding.progressBar.progress = state.sent + state.failed

            // Joriy holat matni
            binding.tvCurrentStatus.text = state.currentMessage
        } else {
            binding.tvProgress.text = getString(R.string.progress_idle)
            binding.progressBar.progress = 0
            binding.tvCurrentStatus.text = ""
        }
    }

    /**
     * Yuborish holatiga qarab tugmalarni yoqadi/o'chiradi.
     */
    private fun updateButtonStates(isRunning: Boolean) {
        binding.btnStart.isEnabled = !isRunning && contacts.isNotEmpty()
        binding.btnStop.isEnabled = isRunning
        binding.btnSelectFile.isEnabled = !isRunning
        binding.checkSkipHeader.isEnabled = !isRunning
    }

    // ─── Fayl tanlash ────────────────────────────────────────────────────────

    /**
     * Fayl tanlanganda ishga tushadi.
     * CSV faylni o'qib, kontaktlar ro'yxatini hosil qiladi.
     */
    private fun onFileSelected(uri: Uri) {
        try {
            // Fayl nomini aniqlaymiz (foydalanuvchiga ko'rsatish uchun)
            val fileName = getFileName(uri)
            binding.tvFileName.text = "📄 $fileName"

            // CSV faylni o'qiymiz
            val skipHeader = binding.checkSkipHeader.isChecked
            contacts = CsvParser.parse(this, uri, skipHeader)

            if (contacts.isEmpty()) {
                showToast(getString(R.string.error_empty_file))
                binding.tvContactCount.text = ""
                binding.btnStart.isEnabled = false
            } else {
                // O'qilgan raqamlar sonini ko'rsatamiz
                binding.tvContactCount.text = "✓ ${contacts.size} ta raqam o'qildi"
                binding.btnStart.isEnabled = true
                showToast("${contacts.size} ta kontakt muvaffaqiyatli yuklandi")
            }

        } catch (e: Exception) {
            showToast("Fayl o'qishda xato: ${e.message}")
            binding.tvFileName.text = "❌ Fayl o'qib bo'lmadi"
            binding.tvContactCount.text = ""
            binding.btnStart.isEnabled = false
        }
    }

    /**
     * URI dan fayl nomini oladi.
     */
    private fun getFileName(uri: Uri): String {
        // ContentResolver orqali fayl nomini so'raymiz
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    return it.getString(nameIndex) ?: "fayl.csv"
                }
            }
        }
        // Agar nom topilmasa, URI dan oxirgi qismni olamiz
        return uri.lastPathSegment ?: "fayl.csv"
    }

    // ─── SMS yuborish ─────────────────────────────────────────────────────────

    /**
     * "Yuborishni boshlash" tugmasi bosilganda.
     */
    private fun onStartClicked() {
        if (contacts.isEmpty()) {
            showToast(getString(R.string.error_no_file))
            return
        }

        // SMS ruxsatini tekshiramiz
        checkSmsPermissionAndStart()
    }

    /**
     * "To'xtatish" tugmasi bosilganda.
     */
    private fun onStopClicked() {
        AlertDialog.Builder(this)
            .setTitle("Yuborishni to'xtatish")
            .setMessage("Hozirgi SMS yuborilgandan so'ng jarayon to'xtatiladi. Davom ettirasizmi?")
            .setPositiveButton("Ha, to'xtat") { _, _ ->
                viewModel.stopSending()
                showToast("To'xtatilmoqda...")
            }
            .setNegativeButton("Yo'q, davom et", null)
            .show()
    }

    /**
     * SMS ruxsatini tekshiradi:
     * - Agar ruxsat mavjud bo'lsa — yuborishni boshlaydi
     * - Agar ruxsat yo'q bo'lsa — so'raydi
     */
    private fun checkSmsPermissionAndStart() {
        when {
            // Ruxsat allaqachon berilgan
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED -> {
                startSendingIfReady()
            }

            // Ruxsat so'rash oldidan tushuntirish kerak (foydalanuvchi oldin rad etgan)
            shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS) -> {
                AlertDialog.Builder(this)
                    .setTitle("SMS Ruxsati kerak")
                    .setMessage(getString(R.string.permission_rationale))
                    .setPositiveButton("Ruxsat berish") { _, _ ->
                        requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                    }
                    .setNegativeButton("Bekor qilish", null)
                    .show()
            }

            // Ruxsat so'raymiz
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            }
        }
    }

    /**
     * Ruxsat tekshirilgandan so'ng yuborishni boshlaydi.
     */
    private fun startSendingIfReady() {
        if (contacts.isEmpty()) {
            showToast(getString(R.string.error_no_file))
            return
        }

        // Tasdiq dialogi — tasodifan bosib yuborilmasin
        AlertDialog.Builder(this)
            .setTitle("Yuborishni boshlash")
            .setMessage("${contacts.size} ta raqamga SMS yuboriladi.\nDavom etishni xohlaysizmi?")
            .setPositiveButton("Ha, boshlash") { _, _ ->
                viewModel.startSending(contacts)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    // ─── Ruxsat rad etilganda ────────────────────────────────────────────────

    /**
     * Ruxsat rad etilganda foydalanuvchiga yo'riqnoma ko'rsatadi.
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("SMS Ruxsati berilmadi")
            .setMessage(
                "SMS yuborish uchun ruxsat talab qilinadi.\n\n" +
                "Ruxsat berish uchun:\n" +
                "1. Telefon Sozlamalari → Ilovalar\n" +
                "2. SMS Manager → Ruxsatlar\n" +
                "3. SMS → Ruxsat berish"
            )
            .setPositiveButton("Tushundim", null)
            .show()
    }

    // ─── Yordamchi ─────────────────────────────────────────────────────────────

    /**
     * Qisqa Toast xabarini ko'rsatadi.
     */
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
