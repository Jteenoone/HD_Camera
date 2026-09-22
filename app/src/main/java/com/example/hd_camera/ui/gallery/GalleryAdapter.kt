package com.example.hd_camera.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import com.example.hd_camera.databinding.ItemGalleryPhotoBinding
import com.example.hd_camera.media.MediaItem
import java.util.Locale

/** The grid of one date section. */
class GalleryAdapter(
    private val items: List<MediaItem>,
    private val isSelected: (MediaItem) -> Boolean,
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.ThumbViewHolder>() {

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
        val binding = ItemGalleryPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ThumbViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ThumbViewHolder(private val binding: ItemGalleryPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.imgThumb.load(item.uri) {
                crossfade(true)
                // The opening frame of a clip is usually still black while exposure settles,
                // so the tile takes one from just under a second in. A shorter clip than that
                // falls back to its closest key frame rather than coming up empty.
                if (item.isVideo) videoFrameMillis(VIDEO_THUMB_MS)
            }

            binding.tvDuration.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            if (item.isVideo) {
                val seconds = item.durationMillis / 1000
                binding.tvDuration.text =
                    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            }

            binding.selectionOverlay.visibility =
                if (isSelected(item)) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private companion object {
        const val VIDEO_THUMB_MS = 600L
    }
}
