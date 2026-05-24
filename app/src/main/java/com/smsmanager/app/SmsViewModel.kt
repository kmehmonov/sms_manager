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
 * Har bir SMS uchun log yozuvi modeli.
 * UI'da ro'yxat sifatida ko'rsatiladi.
 */
data class SmsLogEntry(
    val index: Int,
    val phoneNumber: String,
    val messagePreview: String,  // Xabarning birinchi 50 belgisi
    var status: SmsStatus,
    var errorMessage: String? = null
)

/**
 * SMS holat turlari.
 */
enum class SmsStatus {
    PENDING,   // Navbatda kutmoqda
    SENDING,   // Yuborilmoqda
    SENT,      // Muvaffaqiyatli yuborildi
    FAILED     // Xato yuz berdi
}

/**
 * Umumiy yuborish holati — UI uchun.
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
 *
 * AndroidViewModel ishlatilmoqda — Application kontekstiga ega bo'lish uchun.
 * SmsManager API'si kontekst talab qilgani uchun zarur.
 */
class SmsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SmsViewModel"

        // Har bir SMS orasidagi minimal kutish vaqti (millisekund)
        private const val MIN_DELAY_MS = 4000L
        // Har bir SMS orasidagi maksimal kutish vaqti (millisekund)
        private const val MAX_DELAY_MS = 6000L

        // Har 20 ta SMS dan keyin uzunroq tanaffus
        private const val BATCH_SIZE = 20
        private const val MIN_BATCH_DELAY_MS = 30_000L  // 30 sekund
        private const val MAX_BATCH_DELAY_MS = 60_000L  // 60 sekund
    }

    // ─── LiveData'lar — UI observ qiladi ───────────────────────────────────

    // SMS log ro'yxati (har bir SMS uchun yozuv)
    private val _logEntries = MutableLiveData<List<SmsLogEntry>>(emptyList())
    val logEntries: LiveData<List<SmsLogEntry>> = _logEntries

    // Umumiy yuborish holati
    private val _sendingState = MutableLiveData(SendingState())
    val sendingState: LiveData<SendingState> = _sendingState

    // Xato xabarlari (bir martalik)
    private val _errorEvent = MutableLiveData<String?>()
    val errorEvent: LiveData<String?> = _errorEvent

    // ─── Ichki o'zgaruvchilar ────────────────────────────────────────────────

    // Yuborish jarayonini to'xtatish uchun Job
    private var sendingJob: Job? = null

    // Joriy log yozuvlari ro'yxatining o'zgaruvchan nusxasi
    private val currentEntries = mutableListOf<SmsLogEntry>()

    /**
     * CSV kontaktlar ro'yxatiga asosan SMS yuborishni boshlaydi.
     * Coroutine fon rejimida ishlaydi — UI qotib qolmaydi.
     */
    fun startSending(contacts: List<SmsContact>) {
        // Eski ishni to'xtatamiz (agar bo'lsa)
        sendingJob?.cancel()

        // Log ro'yxatini tayyorlaymiz — barchasi "kutilmoqda" holatida
        currentEntries.clear()
        currentEntries.addAll(contacts.map { contact ->
            SmsLogEntry(
                index = contact.index,
                phoneNumber = contact.phoneNumber,
                messagePreview = contact.message.take(60) + if (contact.message.length > 60) "..." else "",
                status = SmsStatus.PENDING
            )
        })
        _logEntries.postValue(currentEntries.toList())

        // Yuborish holatini yangilaymiz
        _sendingState.postValue(
            SendingState(
                isRunning = true,
                sent = 0,
                failed = 0,
                total = contacts.size,
                currentMessage = "Tayyorlanmoqda..."
            )
        )

        // Fon rejimida ishni boshlaymiz
        sendingJob = viewModelScope.launch(Dispatchers.IO) {
            var sentCount = 0
            var failedCount = 0

            contacts.forEachIndexed { i, contact ->
                // Agar to'xtatilgan bo'lsa — chiqamiz
                if (!isActive) {
                    Log.d(TAG, "Yuborish to'xtatildi, ${i}/${contacts.size} bajarildi")
                    return@forEachIndexed
                }

                // Joriy elementni "yuborilmoqda" holatiga o'tkazamiz
                updateEntryStatus(contact.index, SmsStatus.SENDING)

                // UI'ga joriy holat haqida xabar beramiz
                withContext(Dispatchers.Main) {
                    _sendingState.value = _sendingState.value?.copy(
                        currentMessage = "${contact.phoneNumber} ga yuborilmoqda..."
                    )
                }

                // Telefon raqamini tekshiramiz
                if (!CsvParser.isValidPhoneNumber(contact.phoneNumber)) {
                    Log.w(TAG, "Noto'g'ri raqam: ${contact.phoneNumber}")
                    failedCount++
                    updateEntryStatus(
                        index = contact.index,
                        status = SmsStatus.FAILED,
                        error = "Noto'g'ri raqam formati"
                    )
                } else {
                    // SMS yuborishga harakat qilamiz
                    val success = sendSms(
                        context = getApplication(),
                        phoneNumber = contact.phoneNumber,
                        message = contact.message
                    )

                    if (success) {
                        sentCount++
                        updateEntryStatus(contact.index, SmsStatus.SENT)
                        Log.d(TAG, "✓ Yuborildi: ${contact.phoneNumber}")
                    } else {
                        failedCount++
                        updateEntryStatus(
                            index = contact.index,
                            status = SmsStatus.FAILED,
                            error = "Yuborishda xato yuz berdi"
                        )
                        Log.w(TAG, "✗ Xato: ${contact.phoneNumber}")
                    }
                }

                // Progress'ni yangilaymiz
                val currentSent = sentCount
                val currentFailed = failedCount
                withContext(Dispatchers.Main) {
                    _sendingState.value = _sendingState.value?.copy(
                        sent = currentSent,
                        failed = currentFailed
                    )
                }

                // Keyingi SMSdan oldin kutamiz (oxirgi element bo'lmasa)
                if (i < contacts.size - 1 && isActive) {
                    // Har 20 ta SMSdan keyin uzunroq tanaffus
                    val isEndOfBatch = (i + 1) % BATCH_SIZE == 0
                    val delayMs = if (isEndOfBatch) {
                        val pause = (MIN_BATCH_DELAY_MS..MAX_BATCH_DELAY_MS).random()
                        Log.d(TAG, "20 ta SMS yuborildi. ${pause/1000}s tanaffus...")
                        withContext(Dispatchers.Main) {
                            _sendingState.value = _sendingState.value?.copy(
                                currentMessage = "20 ta SMS yuborildi. ${pause/1000}s tanaffus..."
                            )
                        }
                        pause
                    } else {
                        // Oddiy SMS orasidagi kutish (4-6 sekund)
                        (MIN_DELAY_MS..MAX_DELAY_MS).random()
                    }

                    delay(delayMs)
                }
            }

            // Yuborish tugadi — yakuniy holatni yangilaymiz
            withContext(Dispatchers.Main) {
                _sendingState.value = SendingState(
                    isRunning = false,
                    sent = sentCount,
                    failed = failedCount,
                    total = contacts.size,
                    currentMessage = if (isActive) {
                        "✓ Yuborish tugadi! Muvaffaqiyatli: $sentCount, Xato: $failedCount"
                    } else {
                        "⏹ To'xtatildi. Muvaffaqiyatli: $sentCount, Xato: $failedCount"
                    }
                )
            }
        }
    }

    /**
     * SMS yuborishni to'xtatadi.
     * Joriy SMS yuboriladi, undan keyingilari bekor qilinadi.
     */
    fun stopSending() {
        sendingJob?.cancel()
        Log.d(TAG, "Yuborish to'xtatilish buyrug'i berildi")
    }

    /**
     * Log ro'yxatini tozalaydi.
     */
    fun clearLog() {
        currentEntries.clear()
        _logEntries.postValue(emptyList())
        _sendingState.postValue(SendingState())
    }

    /**
     * Xatoni "ko'rib bo'lindi" deb belgilaydi.
     */
    fun onErrorShown() {
        _errorEvent.postValue(null)
    }

    // ─── Yordamchi funksiyalar ────────────────────────────────────────────────

    /**
     * SmsManager orqali SMS yuboradi.
     * Uzun xabarlar avtomatik bo'laklarga bo'linadi.
     *
     * @return true — muvaffaqiyatli, false — xato
     */
    private fun sendSms(context: Context, phoneNumber: String, message: String): Boolean {
        return try {
            // SmsManager — SMS yuborish uchun Android API
            @Suppress("DEPRECATION")
            val smsManager: SmsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }

            // Xabarni bo'laklarga bo'lamiz (160 belgidan uzun bo'lsa)
            // divideMessage() Unicodeni ham hisobga oladi (70 belgi/qism)
            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                // Qisqa xabar — oddiy yuborish
                smsManager.sendTextMessage(
                    phoneNumber,  // Qabul qiluvchi raqami
                    null,         // Yuboruvchi raqami (null = sim karta)
                    message,      // Xabar matni
                    null,         // Yuborilganda olib keluvchi PendingIntent
                    null          // Yetib borganda olib keluvchi PendingIntent
                )
            } else {
                // Uzun xabar — ko'p qismli yuborish
                Log.d(TAG, "Uzun xabar: ${parts.size} qismga bo'lindi")
                smsManager.sendMultipartTextMessage(
                    phoneNumber,
                    null,
                    parts,
                    null,
                    null
                )
            }

            true // Muvaffaqiyatli
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Noto'g'ri argument: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "SMS yuborishda xato [${phoneNumber}]: ${e.message}", e)
            false
        }
    }

    /**
     * Log yozuvining holatini yangilaydi va UI'ga xabar beradi.
     */
    private fun updateEntryStatus(index: Int, status: SmsStatus, error: String? = null) {
        val entryIndex = currentEntries.indexOfFirst { it.index == index }
        if (entryIndex != -1) {
            currentEntries[entryIndex] = currentEntries[entryIndex].copy(
                status = status,
                errorMessage = error
            )
            // Yangi ro'yxat nusxasini yuboramiz — LiveData kuzatuvchilarni xabardor qiladi
            _logEntries.postValue(currentEntries.toList())
        }
    }

    /**
     * Random oraliqdan son tanlash yordamchi funksiyasi.
     */
    private fun LongRange.random(): Long {
        return first + (Math.random() * (last - first + 1)).toLong()
    }
}
