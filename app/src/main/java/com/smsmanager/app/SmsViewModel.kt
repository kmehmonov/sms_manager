package com.smsmanager.app

import android.app.Application
import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Har bir SMS uchun log yozuvi.
 */
data class SmsLogEntry(
    val index: Int,
    val phoneNumber: String,
    val message: String,          // To'liq xabar (dialog uchun)
    val messagePreview: String,   // Qisqa ko'rinish (1 qator)
    var status: SmsStatus,
    var errorMessage: String? = null
)

enum class SmsStatus {
    PENDING, SENDING, SENT, FAILED
}

/**
 * Umumiy yuborish holati.
 */
data class SendingState(
    val isRunning: Boolean = false,
    val sent: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    val currentMessage: String = ""
)

/**
 * SMS yuborish biznes mantiqini boshqaruvchi ViewModel.
 */
class SmsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SmsViewModel"
        // Har 20 ta SMS dan keyin uzun tanaffus
        private const val BATCH_SIZE = 20
        private const val MIN_BATCH_DELAY_MS = 30_000L
        private const val MAX_BATCH_DELAY_MS = 60_000L
    }

    // ─── LiveData'lar ──────────────────────────────────────────────────────────
    private val _logEntries = MutableLiveData<List<SmsLogEntry>>(emptyList())
    val logEntries: LiveData<List<SmsLogEntry>> = _logEntries

    private val _sendingState = MutableLiveData(SendingState())
    val sendingState: LiveData<SendingState> = _sendingState

    private val _errorEvent = MutableLiveData<String?>()
    val errorEvent: LiveData<String?> = _errorEvent

    // ─── Ichki o'zgaruvchilar ──────────────────────────────────────────────────
    private var sendingJob: Job? = null
    private val currentEntries = mutableListOf<SmsLogEntry>()

    /**
     * SMS yuborishni boshlaydi.
     *
     * @param contacts     Kontaktlar ro'yxati (CSV dan o'qilgan)
     * @param minDelayMs   SMS'lar orasidagi minimal kutish vaqti (millisekund)
     * @param maxDelayMs   SMS'lar orasidagi maksimal kutish vaqti (millisekund)
     */
    fun startSending(
        contacts: List<SmsContact>,
        minDelayMs: Long = 4_000L,
        maxDelayMs: Long = 6_000L
    ) {
        sendingJob?.cancel()

        // Log ro'yxatini tayyorlaymiz
        currentEntries.clear()
        currentEntries.addAll(contacts.map { contact ->
            SmsLogEntry(
                index = contact.index,
                phoneNumber = contact.phoneNumber,
                message = contact.message,
                messagePreview = contact.message.take(60) +
                        if (contact.message.length > 60) "…" else "",
                status = SmsStatus.PENDING
            )
        })
        _logEntries.postValue(currentEntries.toList())
        _sendingState.postValue(
            SendingState(isRunning = true, total = contacts.size, currentMessage = "Tayyorlanmoqda…")
        )

        sendingJob = viewModelScope.launch(Dispatchers.IO) {
            var sentCount = 0
            var failedCount = 0

            contacts.forEachIndexed { i, contact ->
                if (!isActive) return@forEachIndexed

                updateEntryStatus(contact.index, SmsStatus.SENDING)
                withContext(Dispatchers.Main) {
                    _sendingState.value = _sendingState.value?.copy(
                        currentMessage = "${contact.phoneNumber} ga yuborilmoqda…"
                    )
                }

                // Raqam validatsiyasi
                if (!CsvParser.isValidPhoneNumber(contact.phoneNumber)) {
                    failedCount++
                    updateEntryStatus(contact.index, SmsStatus.FAILED, "Noto'g'ri raqam formati")
                    Log.w(TAG, "Noto'g'ri raqam: ${contact.phoneNumber}")
                } else {
                    val success = sendSms(getApplication(), contact.phoneNumber, contact.message)
                    if (success) {
                        sentCount++
                        updateEntryStatus(contact.index, SmsStatus.SENT)
                        Log.d(TAG, "✓ ${contact.phoneNumber}")
                    } else {
                        failedCount++
                        updateEntryStatus(contact.index, SmsStatus.FAILED, "Yuborishda xato")
                        Log.w(TAG, "✗ ${contact.phoneNumber}")
                    }
                }

                // Progress yangilash
                val s = sentCount; val f = failedCount
                withContext(Dispatchers.Main) {
                    _sendingState.value = _sendingState.value?.copy(sent = s, failed = f)
                }

                // Oxirgi element bo'lmasa — kutish
                if (i < contacts.size - 1 && isActive) {
                    val isEndOfBatch = (i + 1) % BATCH_SIZE == 0
                    val waitMs = if (isEndOfBatch) {
                        val pause = randomLong(MIN_BATCH_DELAY_MS, MAX_BATCH_DELAY_MS)
                        Log.d(TAG, "Batch tanaffus: ${pause / 1000}s")
                        withContext(Dispatchers.Main) {
                            _sendingState.value = _sendingState.value?.copy(
                                currentMessage = "Batch tanaffus: ${pause / 1000} sekund…"
                            )
                        }
                        pause
                    } else {
                        // Foydalanuvchi belgilagan diapazonda random kutish
                        randomLong(minDelayMs, maxDelayMs)
                    }
                    delay(waitMs)
                }
            }

            // Yakuniy holat
            val finalSent = sentCount; val finalFailed = failedCount
            withContext(Dispatchers.Main) {
                val msg = if (isActive)
                    "✅ Tugadi! Muvaffaqiyatli: $finalSent, Xato: $finalFailed"
                else
                    "⏹ To'xtatildi. Muvaffaqiyatli: $finalSent, Xato: $finalFailed"
                _sendingState.value = SendingState(
                    isRunning = false,
                    sent = finalSent,
                    failed = finalFailed,
                    total = contacts.size,
                    currentMessage = msg
                )
            }
        }
    }

    fun stopSending() {
        sendingJob?.cancel()
    }

    fun clearLog() {
        currentEntries.clear()
        _logEntries.postValue(emptyList())
        _sendingState.postValue(SendingState())
    }

    fun onErrorShown() {
        _errorEvent.postValue(null)
    }

    // ─── Yordamchi funksiyalar ─────────────────────────────────────────────────

    private fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val smsManager: SmsManager =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                Log.d(TAG, "Uzun xabar: ${parts.size} qism — ${contact(phoneNumber)}")
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS xato [$phoneNumber]: ${e.message}")
            false
        }
    }

    private fun updateEntryStatus(index: Int, status: SmsStatus, error: String? = null) {
        val idx = currentEntries.indexOfFirst { it.index == index }
        if (idx != -1) {
            currentEntries[idx] = currentEntries[idx].copy(status = status, errorMessage = error)
            _logEntries.postValue(currentEntries.toList())
        }
    }

    /** Ikki qiymat orasidan tasodifiy son (ikkala chegara ham ichki) */
    private fun randomLong(min: Long, max: Long): Long {
        if (min >= max) return min
        return min + (Math.random() * (max - min + 1)).toLong()
    }

    // Faqat log uchun qisqa yordamchi
    private fun contact(phone: String) = phone
}
