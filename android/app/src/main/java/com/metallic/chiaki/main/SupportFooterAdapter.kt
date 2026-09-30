// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.databinding.ItemSupportBinding

/**
 * A quiet note below the consoles that the app is free, with a link to support it.
 */
class SupportFooterAdapter(private val supportCallback: () -> Unit): RecyclerView.Adapter<SupportFooterAdapter.ViewHolder>()
{
	class ViewHolder(val binding: ItemSupportBinding): RecyclerView.ViewHolder(binding.root)

	var visible = false
		set(value)
		{
			if(field == value)
				return
			field = value
			if(value)
				notifyItemInserted(0)
			else
				notifyItemRemoved(0)
		}

	override fun getItemCount() = if(visible) 1 else 0

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
		ViewHolder(ItemSupportBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also {
			it.binding.supportButton.setOnClickListener { supportCallback() }
		}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
}
