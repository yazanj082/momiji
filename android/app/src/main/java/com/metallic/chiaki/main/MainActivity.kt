// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.BuildConfig
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.databinding.SheetAddConsoleBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.regist.showRemoveRegistrationDialog
import com.metallic.chiaki.session.RestModeRequest
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.shortcut.ConsoleShortcuts
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.common.ext.fitSystemBars

class MainActivity : AppCompatActivity()
{
	companion object
	{
		// A PS5 takes around 20s to wake up from rest mode
		private const val WAKEUP_CONNECT_TIMEOUT_MS = 90000L
		private const val MIN_CARD_WIDTH_DP = 320

		/** MacAddress value of a registered console, to connect to as soon as it is listed */
		const val EXTRA_CONNECT_HOST_MAC = "connect_host_mac"
		/** Its name, while it isn't listed yet */
		const val EXTRA_CONNECT_HOST_NAME = "connect_host_name"
		const val ACTION_PLAY = BuildConfig.APPLICATION_ID + ".action.PLAY"
		/** Discovery, waking up and PSN take a while, but not this long */
		private const val CONNECT_HOST_TIMEOUT_MS = 25000L

		/** Plays the registered console, from shortcuts, the tile and the widget */
		fun playIntent(context: Context, mac: Long, name: String?) = Intent(context, MainActivity::class.java)
			.setAction(ACTION_PLAY)
			.putExtra(EXTRA_CONNECT_HOST_MAC, mac)
			.putExtra(EXTRA_CONNECT_HOST_NAME, name)
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
	}

	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private lateinit var layoutManager: GridLayoutManager
	private var discoveryMenuItem: MenuItem? = null

	private val supportUrl get() = getString(R.string.support_url)
	private var connectHostMac: Long? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars()

		title = ""
		setSupportActionBar(binding.toolbar)

		binding.floatingActionButton.setOnClickListener { showAddConsoleSheet() }
		binding.emptyDiscoverButton.setOnClickListener { viewModel.discoveryManager.active = true }
		binding.emptyInternetPlayButton.setOnClickListener { startActivity(SettingsActivity.internetPlayIntent(this)) }

		viewModel = ViewModelProvider(this, viewModelFactory {
			MainViewModel(getDatabase(this), Preferences(this), PsnAccount(this), getString(R.string.display_host_main_ps4))
		}).get(MainViewModel::class.java)

		val hostsAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::putInRestMode,
			this::registerHostAgain, this::removeRegistration, this::editHost, this::deleteHost,
			this::addToHomeScreen.takeIf { ConsoleShortcuts.canPin(this) })
		val supportAdapter = SupportFooterAdapter(this::openSupportPage)
		binding.hostsRecyclerView.adapter = ConcatAdapter(hostsAdapter, supportAdapter)
		layoutManager = GridLayoutManager(this, 1)
		layoutManager.spanSizeLookup = object: GridLayoutManager.SpanSizeLookup()
		{
			override fun getSpanSize(position: Int) = if(position < hostsAdapter.itemCount) 1 else layoutManager.spanCount
		}
		binding.hostsRecyclerView.layoutManager = layoutManager
		updateSpanCount()
		binding.hostsRecyclerView.addOnScrollListener(object: RecyclerView.OnScrollListener()
		{
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int)
			{
				if(dy > 0)
					binding.floatingActionButton.shrink()
				else if(dy < 0)
					binding.floatingActionButton.extend()
			}
		})
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
			hostsAdapter.hosts = it
			supportAdapter.visible = it.isNotEmpty() && supportUrl.isNotEmpty()
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
			connectIfWokenUp(it)
			connectRegisteredHost(it)
			// With a controller, the first console is ready to be picked right away
			if(currentFocus == null && it.isNotEmpty() && Preferences(this).isTv)
				binding.hostsRecyclerView.post {
					binding.hostsRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
				}
		})

		viewModel.discoveryActive.observe(this, Observer { active: Boolean ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})
		viewModel.hasRegisteredHosts.observe(this, Observer { updateEmptyInfo() })
		viewModel.registeredHosts.observe(this, Observer { ConsoleShortcuts.update(this, it) })
		// Not again when the activity is recreated
		if(savedInstanceState == null)
			connectHostFromIntent(intent)
		viewModel.psnSignInExpired.observe(this, Observer { expired ->
			if(expired)
				showPsnSignInExpired()
		})
	}

	private fun showPsnSignInExpired()
	{
		viewModel.psnSignInExpiredShown()
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.internet_play_sign_in_expired_title)
			.setMessage(R.string.internet_play_sign_in_expired)
			.setPositiveButton(R.string.internet_play_sign_in) { _, _ -> startActivity(SettingsActivity.internetPlayIntent(this)) }
			.setNegativeButton(R.string.action_not_now, null)
			.show()
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(when
			{
				!discoveryActive -> R.string.display_hosts_empty_discovery_off_info
				isVpnActive() -> R.string.display_hosts_empty_vpn_info
				else -> R.string.display_hosts_empty_discovery_on_info
			})
			binding.emptyDiscoverButton.visibility = if(discoveryActive) View.GONE else View.VISIBLE
			// Registered consoles that aren't here can be played through PSN
			binding.emptyInternetPlayButton.visibility =
				if(viewModel.hasRegisteredHosts.value == true && !viewModel.psnAccount.isSignedIn) View.VISIBLE else View.GONE
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun isVpnActive(): Boolean
	{
		val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
		val network = connectivityManager.activeNetwork ?: return false
		return connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
	}

	override fun onConfigurationChanged(newConfig: Configuration)
	{
		super.onConfigurationChanged(newConfig)
		updateSpanCount()
	}

	private fun updateSpanCount()
	{
		layoutManager.spanCount = (resources.configuration.screenWidthDp / MIN_CARD_WIDTH_DP).coerceAtLeast(1)
	}

	private fun showAddConsoleSheet()
	{
		val dialog = BottomSheetDialog(this)
		val sheet = SheetAddConsoleBinding.inflate(layoutInflater)
		sheet.registerItem.setOnClickListener {
			dialog.dismiss()
			showRegistration()
		}
		sheet.addManualItem.setOnClickListener {
			dialog.dismiss()
			addManualConsole()
		}
		dialog.setContentView(sheet.root)
		dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
		dialog.behavior.skipCollapsed = true
		dialog.show()
	}

	private fun openSupportPage()
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl)))
		}
		catch(e: ActivityNotFoundException)
		{
			// TV boxes may come without a browser
			Toast.makeText(this, supportUrl, Toast.LENGTH_LONG).show()
		}
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
		// Also after signing in or out
		viewModel.refreshPsnConsoles()
		updateEmptyInfo()
		// The settings may have changed which menu items apply
		invalidateOptionsMenu()
	}

	override fun onStop()
	{
		super.onStop()
		stopWakeupConnect()
		stopConnectHost()
		viewModel.discoveryManager.pause()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		val preferences = Preferences(this)
		menu.findItem(R.id.action_android_settings).isVisible = preferences.isTv || preferences.homeScreen
		menu.findItem(R.id.action_support).isVisible = supportUrl.isNotEmpty()
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		R.id.action_android_settings ->
		{
			startActivity(Intent(Settings.ACTION_SETTINGS))
			true
		}

		R.id.action_support ->
		{
			openSupportPage()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.floatingActionButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.floatingActionButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun startStream(host: DisplayHost, connectInfo: ConnectInfo)
	{
		host.registeredHost?.let { ConsoleShortcuts.played(this, ConsoleShortcuts.console(it, host.name)) }
		Intent(this, StreamActivity::class.java).let {
			it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
			// Empty bounds open the stream full screen in Samsung DeX, without the window's title bar.
			// Launch bounds only apply to new tasks, so the stream gets its own window there.
			val options = if(isSamsungDex())
			{
				it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				ActivityOptions.makeBasic().setLaunchBounds(Rect()).toBundle()
			}
			else
				null
			startActivity(it, options)
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		val registeredHost = host.registeredHost
		if(host is PsnDisplayHost)
		{
			val preferences = Preferences(this)
			startStream(host, ConnectInfo(host.isPS5, host.host, registeredHost!!.rpRegistKey, registeredHost.rpKey, preferences.videoProfile,
				enableDualSense = host.isPS5 && preferences.dualSenseEnabled, psnConsoleUid = host.consoleUid))
		}
		else if(registeredHost != null)
		{
			fun connect() {
				val preferences = Preferences(this)
				startStream(host, ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, preferences.videoProfile,
					enableDualSense = host.isPS5 && preferences.dualSenseEnabled))
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			{
				// With a controller on a TV the obvious choice needs no extra dialog
				if(Preferences(this).isTv)
				{
					wakeupAndConnect(host)
					return
				}
				MaterialAlertDialogBuilder(this)
					.setMessage(R.string.alert_message_standby_wakeup)
					.setPositiveButton(R.string.action_wakeup_connect) { _, _ ->
						wakeupAndConnect(host)
					}
					.setNeutralButton(R.string.action_connect_immediately) { _, _ ->
						connect()
					}
					.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> }
					.create()
					.show()
			}
			else
				connect()
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private val wakeupConnectHandler = Handler(Looper.getMainLooper())
	private var wakeupConnectHost: DisplayHost? = null
	private var wakeupConnectDialog: AlertDialog? = null
	private val wakeupConnectTimeout = Runnable {
		stopWakeupConnect()
		Toast.makeText(this, R.string.wakeup_connect_timeout, Toast.LENGTH_LONG).show()
	}

	/**
	 * Wakes the console up and starts the stream as soon as discovery sees it ready.
	 */
	private fun wakeupAndConnect(host: DiscoveredDisplayHost)
	{
		stopWakeupConnect()
		// The console may be awake already, for example when it was turned on while the standby dialog
		// was open. Discovery only reports changes, so waiting for one would last until the timeout.
		val current = viewModel.displayHosts.value?.firstOrNull { it is DiscoveredDisplayHost && it.id == host.id }
		if(current is DiscoveredDisplayHost && current.discoveredHost.state == DiscoveryHost.State.READY)
		{
			hostTriggered(current)
			return
		}
		viewModel.discoveryManager.active = true
		wakeupHost(host)
		wakeupConnectHost = host
		wakeupConnectDialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.wakeup_connect_waiting, host.name ?: host.host))
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> stopWakeupConnect() }
			.setOnCancelListener { stopWakeupConnect() }
			.show()
		wakeupConnectHandler.postDelayed(wakeupConnectTimeout, WAKEUP_CONNECT_TIMEOUT_MS)
	}

	private fun stopWakeupConnect()
	{
		wakeupConnectHandler.removeCallbacks(wakeupConnectTimeout)
		wakeupConnectHost = null
		wakeupConnectDialog?.dismiss()
		wakeupConnectDialog = null
	}

	private fun connectIfWokenUp(hosts: List<DisplayHost>)
	{
		val waitingFor = wakeupConnectHost ?: return
		val host = hosts.firstOrNull {
			it is DiscoveredDisplayHost && it.id == waitingFor.id && it.discoveredHost.state == DiscoveryHost.State.READY
		} ?: return
		stopWakeupConnect()
		hostTriggered(host)
	}

	override fun onNewIntent(intent: Intent)
	{
		super.onNewIntent(intent)
		connectHostFromIntent(intent)
	}

	private val connectHostHandler = Handler(Looper.getMainLooper())
	private var connectHostDialog: AlertDialog? = null
	private var connectHostName: String? = null
	private val connectHostTimeout = Runnable {
		val name = connectHostName
		stopConnectHost()
		val message = getString(R.string.connect_host_not_found, name ?: getString(R.string.connect_host_your_console)) +
			(if(PsnAccount(this).isSignedIn) "" else "\n\n" + getString(R.string.connect_host_not_found_psn))
		MaterialAlertDialogBuilder(this)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.show()
	}

	/** After registration, or to play a console from a shortcut, the tile or the widget */
	private fun connectHostFromIntent(intent: Intent)
	{
		val mac = intent.getLongExtra(EXTRA_CONNECT_HOST_MAC, -1).takeIf { it >= 0 } ?: return
		stopConnectHost()
		stopWakeupConnect()
		connectHostMac = mac
		connectHostName = intent.getStringExtra(EXTRA_CONNECT_HOST_NAME)
		viewModel.discoveryManager.active = true
		viewModel.displayHosts.value?.let { connectRegisteredHost(it) }
		if(connectHostMac == null)
			return
		connectHostDialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.connect_host_looking, connectHostName ?: getString(R.string.connect_host_your_console)))
			.setNegativeButton(R.string.action_connect_cancel_connect) { _, _ -> stopConnectHost() }
			.setOnCancelListener { stopConnectHost() }
			.show()
		connectHostHandler.postDelayed(connectHostTimeout, CONNECT_HOST_TIMEOUT_MS)
	}

	private fun stopConnectHost()
	{
		connectHostHandler.removeCallbacks(connectHostTimeout)
		connectHostMac = null
		connectHostName = null
		connectHostDialog?.dismiss()
		connectHostDialog = null
	}

	/**
	 * Connects once the console is listed: preferably as discovery found it at home, which tells
	 * whether it has to be woken up, otherwise by its address or through PSN.
	 */
	private fun connectRegisteredHost(hosts: List<DisplayHost>)
	{
		val mac = connectHostMac ?: return
		val matches = hosts.filter { it.registeredHost?.serverMac?.value == mac }
		val host = matches.firstOrNull { it is DiscoveredDisplayHost }
			?: matches.firstOrNull().takeIf { viewModel.isLocalSearchDone }
			?: return
		stopConnectHost()
		if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
			wakeupAndConnect(host)
		else
			hostTriggered(host)
	}

	private fun addToHomeScreen(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		if(!ConsoleShortcuts.pin(this, ConsoleShortcuts.console(registeredHost, host.name)))
			Toast.makeText(this, R.string.shortcut_pin_failed, Toast.LENGTH_LONG).show()
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun putInRestMode(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		val name = host.name ?: host.host
		val connectInfo = ConnectInfo(host.isPS5, host.host, registeredHost.rpRegistKey, registeredHost.rpKey, Preferences(this).videoProfile)
		var request: RestModeRequest? = null
		val dialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.rest_mode_running, name))
			.setNegativeButton(android.R.string.cancel) { _, _ -> request?.cancel() }
			.setOnCancelListener { request?.cancel() }
			.show()
		request = RestModeRequest(connectInfo) { success ->
			if(!isDestroyed)
				dialog.dismiss()
			Toast.makeText(applicationContext, getString(if(success) R.string.rest_mode_done else R.string.rest_mode_failed, name), Toast.LENGTH_LONG).show()
		}
	}

	/** Registration again, to play on the console with another PSN account */
	private fun registerHostAgain(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		startActivity(RegistActivity.registerAgainIntent(this, registeredHost, host.name, host.host,
			(host as? ManualDisplayHost)?.manualHost?.id))
	}

	private fun removeRegistration(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		showRemoveRegistrationDialog(this, host.name ?: host.host) {
			viewModel.removeRegistration(registeredHost)
		}
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
