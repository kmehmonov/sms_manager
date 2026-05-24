package com.smsmanager.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smsmanager.app.databinding.ItemLogBinding

/**
 * Log ro'yxati adapteri.
 * Har bir element qisqa ko'rinishda, bosish orqali dialog ochiladi.
 */
class LogAdapter(
    private val onItemClick: (SmsLogEntry) -> Unit
) : ListAdapter<SmsLogEntry, LogAdapter.LogViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(
        private val binding: ItemLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SmsLogEntry) {
            binding.tvIndex.text = "#${entry.index}"
            binding.tvPhone.text = entry.phoneNumber
            binding.tvMessagePreview.text = entry.messagePreview

            // Holat badge matn + ranglar
            when (entry.status) {
                SmsStatus.PENDING -> {
                    binding.tvStatus.text = "⏳"
                    binding.tvStatusLabel.text = "NAVBAT"
                    applyStatusColors(
                        bgCircle = 0xFFF3F4F6.toInt(),
                        textColor = 0xFF6B7280.toInt(),
                        badgeBg = 0xFFF3F4F6.toInt(),
                        badgeText = 0xFF6B7280.toInt()
                    )
                }
                SmsStatus.SENDING -> {
                    binding.tvStatus.text = "📤"
                    binding.tvStatusLabel.text = "KETDI"
                    applyStatusColors(
                        bgCircle = 0xFFEDE9FE.toInt(),
                        textColor = 0xFF7C3AED.toInt(),
                        badgeBg = 0xFFEDE9FE.toInt(),
                        badgeText = 0xFF7C3AED.toInt()
                    )
                }
                SmsStatus.SENT -> {
                    binding.tvStatus.text = "✓"
                    binding.tvStatusLabel.text = "OK"
                    applyStatusColors(
                        bgCircle = 0xFFD1FAE5.toInt(),
                        textColor = 0xFF059669.toInt(),
                        badgeBg = 0xFFD1FAE5.toInt(),
                        badgeText = 0xFF059669.toInt()
                    )
                }
                SmsStatus.FAILED -> {
                    binding.tvStatus.text = "✗"
                    binding.tvStatusLabel.text = "XATO"
                    applyStatusColors(
                        bgCircle = 0xFFFEE2E2.toInt(),
                        textColor = 0xFFDC2626.toInt(),
                        badgeBg = 0xFFFEE2E2.toInt(),
                        badgeText = 0xFFDC2626.toInt()
                    )
                }
            }

            // Bosish — to'liq tafsilot dialog
            binding.root.setOnClickListener { onItemClick(entry) }
        }

        private fun applyStatusColors(
            bgCircle: Int, textColor: Int, badgeBg: Int, badgeText: Int
        ) {
            binding.viewStatusBg.setBackgroundColor(bgCircle)
            // Oval shakli saqlanishi uchun drawable orqali rang o'rnatamiz
            binding.viewStatusBg.background.setTint(bgCircle)
            binding.tvStatus.setTextColor(textColor)
            binding.tvStatusLabel.setTextColor(badgeText)
            binding.tvStatusLabel.background.setTint(badgeBg)
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SmsLogEntry>() {
        override fun areItemsTheSame(a: SmsLogEntry, b: SmsLogEntry) = a.index == b.index
        override fun areContentsTheSame(a: SmsLogEntry, b: SmsLogEntry) = a == b
    }
}
