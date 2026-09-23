package com.example.GramStream

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.GramStream.databinding.FragmentChannelsBinding

class ChannelsFragment : Fragment() {

    private var _binding: FragmentChannelsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ChannelAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChannelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChannelAdapter(Settings.chatIds.toList()) { channelIdToRemove ->
            Settings.removeChatId(channelIdToRemove)
            CatalogRepository.invalidateCache()
            refreshList()
        }

        binding.channelsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.channelsRecyclerView.adapter = adapter

        binding.addChannelButton.setOnClickListener {
            val input = binding.channelIdInput.text.toString().trim()
            val id = input.toLongOrNull()

            if (id == null || id == 0L) {
                Toast.makeText(requireContext(), "Enter a valid numeric Chat ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Settings.addChatId(id)
            CatalogRepository.invalidateCache()
            binding.channelIdInput.text?.clear()
            refreshList()
            Toast.makeText(requireContext(), "Channel added", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshList() {
        adapter.updateChannels(Settings.chatIds.toList())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}