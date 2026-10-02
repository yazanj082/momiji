// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.FragmentSettingsRegisteredHostsBinding
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.regist.showRemoveRegistrationDialog

class SettingsRegisteredHostsFragment: AppCompatDialogFragment(), TitleFragment
{
	private lateinit var viewModel: SettingsRegisteredHostsViewModel

	private var _binding: FragmentSettingsRegisteredHostsBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
		FragmentSettingsRegisteredHostsBinding.inflate(inflater, container, false).let {
			_binding = it
			it.root
		}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		val context = requireContext()
		viewModel = ViewModelProvider(this, viewModelFactory { SettingsRegisteredHostsViewModel(getDatabase(context)) })
			.get(SettingsRegisteredHostsViewModel::class.java)

		val adapter = SettingsRegisteredHostsAdapter(this::showHostOptions)
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(context)
		binding.hostsRecyclerView.adapter = adapter
		val itemTouchSwipeCallback = object : ItemTouchSwipeCallback(context)
		{
			override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int)
			{
				val pos = viewHolder.adapterPosition
				val host = viewModel.registeredHosts.value?.getOrNull(pos) ?: return
				removeRegistration(host) {
					adapter.notifyItemChanged(pos) // to reset the swipe
				}
			}
		}
		ItemTouchHelper(itemTouchSwipeCallback).attachToRecyclerView(binding.hostsRecyclerView)
		viewModel.registeredHosts.observe(this, Observer {
			// A removed row takes the focus of a remote or controller with it
			val listHadFocus = binding.hostsRecyclerView.hasFocus()
			adapter.hosts = it
			binding.emptyInfoGroup.visibility = if(it.isEmpty()) View.VISIBLE else View.GONE
			if(listHadFocus)
				binding.hostsRecyclerView.post {
					if(_binding == null || binding.hostsRecyclerView.hasFocus())
						return@post
					if(it.isEmpty())
						binding.floatingActionButton.requestFocus()
					else
						binding.hostsRecyclerView.getChildAt(0)?.requestFocus()
				}
		})
		viewModel.manualHosts.observe(this, Observer {})

		binding.floatingActionButton.setOnClickListener {
			Intent(context, RegistActivity::class.java).also {
				it.putRevealExtra(binding.floatingActionButton, binding.rootLayout)
				startActivity(it, ActivityOptions.makeSceneTransitionAnimation(activity).toBundle())
			}
		}
	}

	private fun showHostOptions(host: RegisteredHost)
	{
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(hostName(host))
			.setItems(arrayOf(getString(R.string.action_register_again), getString(R.string.action_remove_registration))) { _, which ->
				when(which)
				{
					0 -> registerAgain(host)
					else -> removeRegistration(host)
				}
			}
			.show()
	}

	private fun registerAgain(host: RegisteredHost)
	{
		val manualHost = viewModel.manualHosts.value?.firstOrNull { it.registeredHost == host.id }
		startActivity(RegistActivity.registerAgainIntent(requireContext(), host, hostName(host), manualHost?.host, manualHost?.id))
	}

	private fun removeRegistration(host: RegisteredHost, keep: () -> Unit = {})
	{
		showRemoveRegistrationDialog(requireContext(), hostName(host), keep) {
			viewModel.removeRegistration(host)
		}
	}

	private fun hostName(host: RegisteredHost) = host.serverNickname ?: host.serverMac.toString()

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_registered_hosts_title)
}