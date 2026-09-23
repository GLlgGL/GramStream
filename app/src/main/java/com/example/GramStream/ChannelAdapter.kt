package com.example.GramStream

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.GramStream.databinding.ItemChannelBinding

class ChannelAdapter(
    private var channels: List<Long>,
    private val onRemoveClicked: (Long) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    class ChannelViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channelId = channels[position]

        holder.binding.channelTitleText.text = "Loading..."
        holder.binding.channelIdSubtext.text = "ID: $channelId"

        // Fetch Channel Title on a background thread
        Thread {
            val title = TdLibManager.getChatTitleBlocking(channelId)
            holder.itemView.post {
                holder.binding.channelTitleText.text = title
            }
        }.start()

        holder.binding.removeChannelButton.setOnClickListener {
            onRemoveClicked(channelId)
        }
    }

    override fun getItemCount(): Int = channels.size

    fun updateChannels(newChannels: List<Long>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}