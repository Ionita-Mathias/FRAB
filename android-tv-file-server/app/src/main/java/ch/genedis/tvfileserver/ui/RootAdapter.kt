package ch.genedis.tvfileserver.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ch.genedis.tvfileserver.R
import ch.genedis.tvfileserver.server.StorageSummary
import ch.genedis.tvfileserver.databinding.ItemRootBinding

/** Lists the exposed storage areas with their free space. */
class RootAdapter : ListAdapter<StorageSummary, RootAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemRootBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRootBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        holder.binding.rootName.text = row.root.displayName
        holder.binding.rootDetail.text = context.getString(
            if (row.root.writable) R.string.root_detail else R.string.root_detail_read_only,
            "/${row.root.id}",
            UiFormat.formatBytes(row.freeBytes),
            UiFormat.formatBytes(row.totalBytes),
        )
        val usedPercent = if (row.totalBytes > 0) {
            (((row.totalBytes - row.freeBytes) * 100) / row.totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        holder.binding.rootUsage.progress = usedPercent
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<StorageSummary>() {
            override fun areItemsTheSame(oldItem: StorageSummary, newItem: StorageSummary): Boolean =
                oldItem.root.id == newItem.root.id

            override fun areContentsTheSame(oldItem: StorageSummary, newItem: StorageSummary): Boolean =
                oldItem == newItem
        }
    }
}
