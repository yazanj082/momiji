// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.databinding.ItemSupportPromptBinding

/**
 * A thank-you above the consoles once someone has played for a while. It asks for support once,
 * and either answer makes it go away for good. Never shown during a stream.
 */
class SupportPromptAdapter(
	private val supportCallback: () -> Unit,
	private val dismissCallback: () -> Unit
): RecyclerView.Adapter<SupportPromptAdapter.ViewHolder>()
{
	companion object
	{
		const val PLAY_TIME_MS = 10 * 60 * 60 * 1000L
	}

	class ViewHolder(val binding: ItemSupportPromptBinding): RecyclerView.ViewHolder(binding.root)

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
		ViewHolder(ItemSupportPromptBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also {
			it.binding.supportButton.setOnClickListener { supportCallback() }
			it.binding.dismissButton.setOnClickListener { dismissCallback() }
		}

	override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
}
