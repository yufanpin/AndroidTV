package com.tivimatelite.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.tivimatelite.databinding.ItemChannelBinding
import com.tivimatelite.model.Channel

class ChannelAdapter(
    private val requestManager: RequestManager,
    private val onChannelFocused: (Channel) -> Unit
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(DIFF_CALLBACK) {
    private var selectedUrl: String? = null
    private var logoLoadingEnabled = true

    init {
        setHasStableIds(false)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        return ChannelViewHolder(
            ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            requestManager,
            onChannelFocused
        )
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position), selectedUrl, logoLoadingEnabled)
    }

    override fun onBindViewHolder(
        holder: ChannelViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_LOGO_STATE)) {
            holder.bindLogo(getItem(position), logoLoadingEnabled)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun setSelectedUrl(url: String) {
        val previous = selectedUrl
        selectedUrl = url
        notifySelected(previous)
        notifySelected(url)
    }

    fun setLogoLoadingEnabled(enabled: Boolean, recyclerView: RecyclerView) {
        if (logoLoadingEnabled == enabled) return
        logoLoadingEnabled = enabled
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION && last >= first) {
            notifyItemRangeChanged(first, last - first + 1, PAYLOAD_LOGO_STATE)
        }
    }

    private fun notifySelected(url: String?) {
        if (url == null) return
        val index = currentList.indexOfFirst { it.streamUrl == url }
        if (index >= 0) notifyItemChanged(index)
    }

    class ChannelViewHolder(
        private val binding: ItemChannelBinding,
        private val requestManager: RequestManager,
        private val onChannelFocused: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val channel = binding.root.tag as? Channel ?: return@setOnFocusChangeListener
                    onChannelFocused(channel)
                }
            }
        }

        fun bind(channel: Channel, selectedUrl: String?, loadLogo: Boolean) {
            binding.root.tag = channel
            binding.root.isSelected = channel.streamUrl == selectedUrl
            binding.channelName.text = channel.name
            bindLogo(channel, loadLogo)
        }

        fun bindLogo(channel: Channel, loadLogo: Boolean) {
            if (loadLogo && !channel.logoUrl.isNullOrBlank()) {
                requestManager
                    .load(channel.logoUrl)
                    .apply(LOGO_OPTIONS)
                    .into(binding.channelLogo)
            } else {
                requestManager.clear(binding.channelLogo)
                binding.channelLogo.setImageDrawable(null)
            }
        }
    }

    companion object {
        private const val PAYLOAD_LOGO_STATE = "logo_state"

        private val LOGO_OPTIONS = RequestOptions()
            .override(64, 64)
            .format(DecodeFormat.PREFER_RGB_565)
            .dontAnimate()

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Channel>() {
            override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
                return oldItem.streamUrl == newItem.streamUrl
            }

            override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
                return oldItem == newItem
            }
        }
    }
}
