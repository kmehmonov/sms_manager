package com.smsmanager.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smsmanager.app.databinding.ItemLogBinding

/**
 * RecyclerView uchun adapter — SMS log ro'yxatini ko'rsatadi.
 *
 * ListAdapter ishlatilmoqda — DiffUtil bilan samarali yangilash uchun.
 * Faqat o'zgargan elementlar qayta chiziladi, butun ro'yxat emas.
 */
class LogAdapter : ListAdapter<SmsLogEntry, LogAdapter.LogViewHolder>(DiffCallback()) {

    /**
     * Yangi ViewHolder yaratadi — bitta log elementi uchun.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LogViewHolder(binding)
    }

    /**
     * ViewHolder'ga ma'lumot bog'laydi.
     */
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Bitta log elementi uchun ViewHolder.
     */
    inner class LogViewHolder(
        private val binding: ItemLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * SmsLogEntry ma'lumotlarini ko'rsatadi.
         */
        fun bind(entry: SmsLogEntry) {
            // Tartib raqami
            binding.tvIndex.text = "#${entry.index}"

            // Telefon raqami
            binding.tvPhone.text = entry.phoneNumber

            // Xabar ko'rinishi (qisqartirilgan)
            binding.tvMessagePreview.text = entry.messagePreview

            // Holat belgisi va rangi
            when (entry.status) {
                SmsStatus.PENDING -> {
                    binding.tvStatus.text = "⏳"
                    binding.tvStatus.setTextColor(
                        binding.root.context.getColor(R.color.pending)
                    )
                    binding.tvError.visibility = View.GONE
                }
                SmsStatus.SENDING -> {
                    binding.tvStatus.text = "📤"
                    binding.tvStatus.setTextColor(
                        binding.root.context.getColor(R.color.warning)
                    )
                    binding.tvError.visibility = View.GONE
                }
                SmsStatus.SENT -> {
                    binding.tvStatus.text = "✓"
                    binding.tvStatus.setTextColor(
                        binding.root.context.getColor(R.color.success)
                    )
                    binding.tvError.visibility = View.GONE
                }
                SmsStatus.FAILED -> {
                    binding.tvStatus.text = "✗"
                    binding.tvStatus.setTextColor(
                        binding.root.context.getColor(R.color.error)
                    )
                    // Xato xabarini ko'rsatamiz
                    if (entry.errorMessage != null) {
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = entry.errorMessage
                    } else {
                        binding.tvError.visibility = View.GONE
                    }
                }
            }

            // Fon rangini holat bo'yicha o'zgartiramiz
            val bgColor = when (entry.status) {
                SmsStatus.SENT -> 0xFFE8F5E9.toInt()    // Yashil fon
                SmsStatus.FAILED -> 0xFFFFEBEE.toInt()  // Qizil fon
                SmsStatus.SENDING -> 0xFFFFF9C4.toInt() // Sariq fon
                SmsStatus.PENDING -> 0xFFFFFFFF.toInt() // Oq fon
            }
            binding.root.setBackgroundColor(bgColor)
        }
    }

    /**
     * DiffUtil — faqat o'zgargan elementlarni topadi va yangilaydi.
     * Ro'yxat yangilanganda butun ro'yxat emas, faqat o'zganlar qayta chiziladi.
     */
    private class DiffCallback : DiffUtil.ItemCallback<SmsLogEntry>() {
        override fun areItemsTheSame(oldItem: SmsLogEntry, newItem: SmsLogEntry): Boolean {
            // Bir xil element ekanligini tekshirish uchun unikal identifikator
            return oldItem.index == newItem.index
        }

        override fun areContentsTheSame(oldItem: SmsLogEntry, newItem: SmsLogEntry): Boolean {
            // Ma'lumotlar o'zgarmagan bo'lsa, qayta chizish shart emas
            return oldItem == newItem
        }
    }
}
