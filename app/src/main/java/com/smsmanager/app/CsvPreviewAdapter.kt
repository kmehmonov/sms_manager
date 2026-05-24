package com.smsmanager.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.smsmanager.app.databinding.ItemCsvPreviewBinding

/**
 * CSV preview dialog uchun adapter.
 * Har bir qatorda telefon raqami va xabar ko'rsatiladi.
 */
class CsvPreviewAdapter(
    private val items: List<SmsContact>
) : RecyclerView.Adapter<CsvPreviewAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCsvPreviewBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(
        private val binding: ItemCsvPreviewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: SmsContact) {
            binding.tvCsvPhone.text = contact.phoneNumber
            binding.tvCsvIndex.text = "#${contact.index}"
            binding.tvCsvMessage.text = contact.message

            // Juft/toq qatorlar uchun alternativ fon rangi
            val bgColor = if (contact.index % 2 == 0) 0xFFF8FAFF.toInt() else 0xFFFFFFFF.toInt()
            binding.root.setBackgroundColor(bgColor)
        }
    }
}
