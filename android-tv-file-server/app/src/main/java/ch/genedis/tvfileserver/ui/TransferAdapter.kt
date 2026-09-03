package ch.genedis.tvfileserver.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ch.genedis.tvfileserver.R
import ch.genedis.tvfileserver.core.transfer.TransferDirection
import ch.genedis.tvfileserver.core.transfer.TransferInfo
import ch.genedis.tvfileserver.databinding.ItemTransferBinding

/**
 * Shows the transfers currently in flight.
 *
 * The rows are not focusable: a D-Pad user must never get trapped in a list that changes
 * under them while a copy is running.
 */
class TransferAdapter : ListAdapter<TransferInfo, TransferAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemTransferBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val transfer = getItem(position)
        val context = holder.itemView.context
        val binding = holder.binding

        binding.transferName.text = transfer.name
        binding.transferIcon.setImageResource(
            if (transfer.direction == TransferDirection.UPLOAD) R.drawable.ic_upload else R.drawable.ic_download,
        )

        val percent = UiFormat.percentOf(transfer.transferred, transfer.total)
        if (percent >= 0) {
            binding.transferProgress.isIndeterminate = false
            binding.transferProgress.progress = percent
        } else {
            binding.transferProgress.isIndeterminate = true
        }

        binding.transferDetail.text = context.getString(
            R.string.transfer_detail,
            transfer.protocol.name,
            UiFormat.formatBytes(transfer.transferred),
            UiFormat.formatSpeed(transfer.bytesPerSecond),
            UiFormat.formatEta(transfer.transferred, transfer.total, transfer.bytesPerSecond),
        )
        binding.root.visibility = View.VISIBLE
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<TransferInfo>() {
            override fun areItemsTheSame(oldItem: TransferInfo, newItem: TransferInfo): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TransferInfo, newItem: TransferInfo): Boolean =
                oldItem == newItem
        }
    }
}
