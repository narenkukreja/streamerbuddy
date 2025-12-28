package com.munch.streamer.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.munch.streamer.R
import com.munch.streamer.databinding.ItemMatchCardBinding
import com.munch.streamer.ui.model.MatchStatus
import com.munch.streamer.ui.model.MatchUi

class MatchAdapter(
    private val onClick: (MatchUi) -> Unit
) : ListAdapter<MatchUi, MatchAdapter.MatchViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MatchViewHolder {
        val binding = ItemMatchCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MatchViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: MatchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<MatchUi>() {
        override fun areItemsTheSame(oldItem: MatchUi, newItem: MatchUi): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MatchUi, newItem: MatchUi): Boolean =
            oldItem == newItem
    }

    class MatchViewHolder(
        private val binding: ItemMatchCardBinding,
        private val onClick: (MatchUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(match: MatchUi) {
            binding.matchTitle.text = match.title
            binding.posterImage.load(match.poster) {
                crossfade(true)
            }

            val (chipText, chipBg, showChip) = when (match.status) {
                MatchStatus.LIVE -> Triple(
                    binding.root.context.getString(R.string.live_badge),
                    R.drawable.bg_live_badge,
                    true
                )
                MatchStatus.DONE -> Triple(
                    binding.root.context.getString(R.string.status_done),
                    R.drawable.bg_status_done,
                    true
                )
                MatchStatus.UPCOMING -> Triple(
                    "",
                    R.drawable.bg_status_upcoming,
                    false
                )
            }

            binding.statusChip.isVisible = showChip
            if (showChip) {
                binding.statusChip.text = chipText
                binding.statusChip.setBackgroundResource(chipBg)
            }
            val exactTime = match.startTimeExact
            val displayTime = if (exactTime.isNotBlank()) exactTime else match.startTimeLabel
            binding.startTimeTop.text = displayTime
            binding.startTimeTop.isVisible = displayTime.isNotBlank()

            binding.root.setOnClickListener { onClick(match) }
        }
    }
}
