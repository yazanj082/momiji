// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.PsnDisplayHost
import com.metallic.chiaki.common.ext.inflate
import com.metallic.chiaki.databinding.ItemDisplayHostBinding
import com.metallic.chiaki.lib.DiscoveryHost

class DisplayHostDiffCallback(val old: List<DisplayHost>, val new: List<DisplayHost>): DiffUtil.Callback()
{
	override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = (old[oldItemPosition] == new[newItemPosition])
	override fun getOldListSize() = old.size
	override fun getNewListSize() = new.size
}

class DisplayHostRecyclerViewAdapter(
	val clickCallback: (DisplayHost) -> Unit,
	val wakeupCallback: (DisplayHost) -> Unit,
	val restModeCallback: (DisplayHost) -> Unit,
	val registerAgainCallback: (DisplayHost) -> Unit,
	val removeRegistrationCallback: (DisplayHost) -> Unit,
	val editCallback: (DisplayHost) -> Unit,
	val deleteCallback: (DisplayHost) -> Unit,
	/** Null if the launcher can't add shortcuts to the home screen */
	val addToHomeScreenCallback: ((DisplayHost) -> Unit)?
): RecyclerView.Adapter<DisplayHostRecyclerViewAdapter.ViewHolder>()
{
	var hosts: List<DisplayHost> = listOf()
		set(value)
		{
			val diff = DiffUtil.calculateDiff(DisplayHostDiffCallback(field, value))
			field = value
			diff.dispatchUpdatesTo(this)
		}

	class ViewHolder(val binding: ItemDisplayHostBinding): RecyclerView.ViewHolder(binding.root)

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int)
		= ViewHolder(ItemDisplayHostBinding.inflate(LayoutInflater.from(parent.context), parent, false))

	override fun getItemCount() = hosts.count()

	override fun onBindViewHolder(holder: ViewHolder, position: Int)
	{
		val context = holder.itemView.context
		val host = hosts[position]
		val state = (host as? DiscoveredDisplayHost)?.discoveredHost?.state
		holder.binding.also {
			val name = host.name ?: host.host
			it.nameTextView.text = name
			// Only discovery or registration tell which console it is
			val consoleType = if(host is DiscoveredDisplayHost || host.isRegistered) (if(host.isPS5) "PS5" else "PS4") else null
			it.hostTextView.text = when
			{
				consoleType == null -> context.getString(R.string.display_host_not_registered)
				host is PsnDisplayHost -> context.getString(R.string.display_host_details, consoleType, context.getString(R.string.display_host_psn))
				name == host.host -> consoleType
				else -> context.getString(R.string.display_host_details, consoleType, host.host)
			}
			val runningApp = (host as? DiscoveredDisplayHost)?.discoveredHost?.runningAppName
			it.bottomInfoTextView.isVisible = runningApp != null
			it.bottomInfoTextView.text = runningApp?.let { app -> context.getString(R.string.display_host_playing, app) }

			val (statusText, statusColor, artBackground) = when
			{
				state == DiscoveryHost.State.READY -> Triple(R.string.display_host_state_ready, R.color.state_ready, R.drawable.console_art_ready)
				state == DiscoveryHost.State.STANDBY -> Triple(R.string.display_host_state_standby, R.color.state_standby, R.drawable.console_art_standby)
				host is PsnDisplayHost -> Triple(R.string.display_host_state_away, R.color.state_unknown, R.drawable.console_art_unknown)
				else -> Triple(R.string.display_host_state_manual, R.color.state_unknown, R.drawable.console_art_unknown)
			}
			it.statusTextView.setText(statusText)
			TextViewCompat.setCompoundDrawableTintList(it.statusTextView, ContextCompat.getColorStateList(context, statusColor))
			it.artLayout.setBackgroundResource(artBackground)
			it.stateIndicatorImageView.setImageResource(
				when(state)
				{
					DiscoveryHost.State.STANDBY -> if(host.isPS5) R.drawable.ic_console_ps5_standby else R.drawable.ic_console_standby
					DiscoveryHost.State.READY -> if(host.isPS5) R.drawable.ic_console_ps5_ready else R.drawable.ic_console_ready
					else -> if(host.isPS5 || consoleType == null) R.drawable.ic_console_ps5 else R.drawable.ic_console
				}
			)

			val (actionText, actionIcon) = when
			{
				!host.isRegistered -> R.string.action_register_short to R.drawable.ic_link
				state == DiscoveryHost.State.STANDBY -> R.string.action_wakeup_play to R.drawable.ic_power
				else -> R.string.action_play to R.drawable.ic_play
			}
			it.primaryActionButton.setText(actionText)
			it.primaryActionButton.setIconResource(actionIcon)
			it.root.setOnClickListener { clickCallback(host) }

			// PSN wakes a console up by itself, and its registration has its own card at home
			val registered = host.isRegistered && host !is PsnDisplayHost
			val canEditDelete = host is ManualDisplayHost
			if(registered || canEditDelete)
			{
				val showMenu = { _: View ->
					val menu = PopupMenu(context, it.menuButton)
					menu.menuInflater.inflate(R.menu.display_host, menu.menu)
					// Consoles added by IP address have no known state, so they get both
					menu.menu.findItem(R.id.action_wakeup).isVisible = registered && state != DiscoveryHost.State.READY
					menu.menu.findItem(R.id.action_rest_mode).isVisible = registered && state != DiscoveryHost.State.STANDBY
					menu.menu.findItem(R.id.action_register_again).isVisible = registered
					menu.menu.findItem(R.id.action_remove_registration).isVisible = registered
					menu.menu.findItem(R.id.action_add_to_home_screen).isVisible = registered && addToHomeScreenCallback != null
					menu.menu.findItem(R.id.action_edit).isVisible = canEditDelete
					menu.menu.findItem(R.id.action_delete).isVisible = canEditDelete
					menu.setOnMenuItemClickListener { menuItem ->
						when(menuItem.itemId)
						{
							R.id.action_wakeup -> wakeupCallback(host)
							R.id.action_rest_mode -> restModeCallback(host)
							R.id.action_register_again -> registerAgainCallback(host)
							R.id.action_remove_registration -> removeRegistrationCallback(host)
							R.id.action_add_to_home_screen -> addToHomeScreenCallback?.invoke(host)
							R.id.action_edit -> editCallback(host)
							R.id.action_delete -> deleteCallback(host)
							else -> return@setOnMenuItemClickListener false
						}
						true
					}
					menu.show()
				}
				it.menuButton.isVisible = true
				it.menuButton.setOnClickListener(showMenu)
				// With a remote or controller, holding the button on a console opens its options,
				// and right goes to their button, which focus search skips as it is inside the card
				it.root.setOnLongClickListener { view -> showMenu(view); true }
				it.root.nextFocusRightId = R.id.menuButton
			}
			else
			{
				it.menuButton.isGone = true
				it.menuButton.setOnClickListener(null)
				it.root.setOnLongClickListener(null)
				it.root.nextFocusRightId = View.NO_ID
			}
		}
	}
}