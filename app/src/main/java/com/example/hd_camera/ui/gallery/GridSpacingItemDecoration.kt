package com.example.hd_camera.ui.gallery

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** The 6px gutter the gallery grid uses, without letting it bleed into the screen padding. */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount

        outRect.left = column * spacing / spanCount
        outRect.right = spacing - (column + 1) * spacing / spanCount
        if (position >= spanCount) outRect.top = spacing
    }
}
