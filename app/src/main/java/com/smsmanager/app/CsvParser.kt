package com.smsmanager.app

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * CSV fayldan telefon raqamlari va xabarlarni o'qish uchun yordamchi sinf.
 *
 * CSV format:
 *   +998901234567,Salom Ahmadjon, bu sizga maxsus taklif!
 *   +998771112233,Hurmatli mijoz, buyurtmangiz tayyor.
 *
 * Muhim: Vergul ichida bo'lgan xabarlar qo'shtirnoq ichida bo'lishi kerak:
 *   +998901234567,"Salom, bu xabar vergul, bilan"
 */
data class SmsContact(
    val index: Int,           // Tartib raqami (1 dan boshlanadi)
    val phoneNumber: String,  // Telefon raqami
    val message: String       // Yuborish kerak bo'lgan xabar
)

object CsvParser {

    private const val TAG = "CsvParser"

    /**
     * URI orqali CSV faylni o'qib, SmsContact ro'yxatini qaytaradi.
     *
     * @param context Android konteksti (ContentResolver uchun)
     * @param uri     File picker orqali olingan fayl URI si
     * @param skipHeader Birinchi qatorni (sarlavhani) o'tkazib yuborish
     * @return O'qilgan kontaktlar ro'yxati
     */
    fun parse(context: Context, uri: Uri, skipHeader: Boolean): List<SmsContact> {
        val contacts = mutableListOf<SmsContact>()

        try {
            // ContentResolver orqali faylni ochamiz — bu usul file picker bilan ishlaydi
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // UTF-8 kodlash bilan o'qiymiz — o'zbek va rus harflari to'g'ri ko'rinadi
                val reader = inputStream.bufferedReader(Charsets.UTF_8)
                var lineNumber = 0
                var contactIndex = 1

                reader.forEachLine { line ->
                    lineNumber++

                    // Birinchi qatorni o'tkazib yuborish (sarlavha)
                    if (skipHeader && lineNumber == 1) {
                        Log.d(TAG, "Sarlavha qatori o'tkazildi: $line")
                        return@forEachLine
                    }

                    // Bo'sh qatorlarni o'tkazib yuborish
                    if (line.isBlank()) return@forEachLine

                    // CSV qatorini tahlil qilamiz
                    val parsed = parseCsvLine(line)

                    // Kamida 2 ta ustun bo'lishi shart: raqam va xabar
                    if (parsed.size < 2) {
                        Log.w(TAG, "$lineNumber-qator noto'g'ri format: $line")
                        return@forEachLine
                    }

                    val phone = parsed[0].trim()
                    // 2-dan keyingi barcha ustunlarni xabar deb qabul qilamiz (vergul bo'lsa)
                    val message = parsed.drop(1).joinToString(",").trim()

                    if (phone.isNotEmpty() && message.isNotEmpty()) {
                        contacts.add(
                            SmsContact(
                                index = contactIndex++,
                                phoneNumber = phone,
                                message = message
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CSV o'qishda xato: ${e.message}", e)
            throw e
        }

        Log.d(TAG, "Jami o'qildi: ${contacts.size} ta kontakt")
        return contacts
    }

    /**
     * CSV qatorini tahlil qiladi — qo'shtirnoq ichidagi vergullarni to'g'ri qayta ishlaydi.
     *
     * Misol:
     *   +998901234567,"Salom, bu test"  →  ["+998901234567", "Salom, bu test"]
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false

        var i = 0
        while (i < line.length) {
            val char = line[i]

            when {
                // Qo'shtirnoqni boshlanishi yoki tugashi
                char == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // Ikki qo'shtirnoq = bitta haqiqiy qo'shtirnoq
                        current.append('"')
                        i++ // keyingi belgini o'tkazib yuboramiz
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                // Vergul — qo'shtirnoq ichida bo'lmasa, ustun ajratgich
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                // Oddiy belgi
                else -> current.append(char)
            }
            i++
        }

        // Oxirgi ustunni qo'shamiz
        result.add(current.toString())
        return result
    }

    /**
     * Telefon raqami to'g'ri formatda ekanligini tekshiradi.
     * Raqam + bilan boshlanishi va kamida 7 raqam bo'lishi kerak.
     */
    fun isValidPhoneNumber(phone: String): Boolean {
        // Faqat raqamlar va + belgisini qoldiramiz
        val cleaned = phone.replace(Regex("[\\s\\-()]"), "")
        // + bilan boshlansa yoki faqat raqamlardan iborat bo'lsa
        return cleaned.matches(Regex("^\\+?[0-9]{7,15}$"))
    }
}
