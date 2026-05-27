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
     * URI orqali CSV faylning birinchi bir nechta qatorini xom holda o'qiydi.
     * Mapping dialogida preview va ustun nomlarini ko'rsatish uchun ishlatiladi.
     *
     * @param context  Android konteksti
     * @param uri      Fayl URI si
     * @param maxRows  O'qiladigan maksimal qator soni (default: 5)
     * @return Har bir qator — ustun qiymatlar ro'yxati
     */
    fun readRawRows(context: Context, uri: Uri, maxRows: Int = 5): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = inputStream.bufferedReader(Charsets.UTF_8)
                for (line in reader.lineSequence()) {
                    if (line.isBlank()) continue
                    rows.add(parseCsvLine(line))
                    if (rows.size >= maxRows) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readRawRows xato: ${e.message}", e)
        }
        return rows
    }

    /**
     * URI orqali CSV faylni o'qib, foydalanuvchi tanlagan ustun mapping asosida
     * SmsContact ro'yxatini qaytaradi.
     *
     * @param context         Android konteksti
     * @param uri             Fayl URI si
     * @param skipHeader      Birinchi qatorni (sarlavhani) o'tkazib yuborish
     * @param phoneColIndex   Telefon raqami joylashgan ustun indeksi (0 dan boshlanadi)
     * @param messageColIndex Xabar joylashgan ustun indeksi (0 dan boshlanadi)
     * @return O'qilgan kontaktlar ro'yxati
     */
    fun parseWithMapping(
        context: Context,
        uri: Uri,
        skipHeader: Boolean,
        phoneColIndex: Int,
        messageColIndex: Int
    ): List<SmsContact> {
        val contacts = mutableListOf<SmsContact>()
        val requiredCols = maxOf(phoneColIndex, messageColIndex) + 1

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = inputStream.bufferedReader(Charsets.UTF_8)
                var lineNumber = 0
                var contactIndex = 1

                reader.forEachLine { line ->
                    lineNumber++

                    if (skipHeader && lineNumber == 1) {
                        Log.d(TAG, "Sarlavha qatori o'tkazildi: $line")
                        return@forEachLine
                    }

                    if (line.isBlank()) return@forEachLine

                    val parsed = parseCsvLine(line)

                    if (parsed.size < requiredCols) {
                        Log.w(TAG, "$lineNumber-qator ustun soni yetarli emas (${parsed.size} < $requiredCols): $line")
                        return@forEachLine
                    }

                    val phone   = parsed[phoneColIndex].trim()
                    val message = parsed[messageColIndex].trim()

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
            Log.e(TAG, "CSV mapping parse xato: ${e.message}", e)
            throw e
        }

        Log.d(TAG, "Jami o'qildi: ${contacts.size} ta kontakt (phone=$phoneColIndex, msg=$messageColIndex)")
        return contacts
    }

    /**
     * @deprecated parseWithMapping() ishlatilsin.
     * Birinchi ustun telefon, ikkinchisi xabar deb qabul qiladi.
     */
    fun parse(context: Context, uri: Uri, skipHeader: Boolean): List<SmsContact> =
        parseWithMapping(context, uri, skipHeader, phoneColIndex = 0, messageColIndex = 1)

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
