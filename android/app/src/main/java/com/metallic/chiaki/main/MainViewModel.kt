// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import com.metallic.chiaki.lib.HolepunchDevice
import com.metallic.chiaki.regist.PsnAuth
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * @param mainPs4Name the name for the PS4 that PSN connects to, when more than one is registered
 */
class MainViewModel(val database: AppDatabase, val preferences: Preferences, val psnAccount: PsnAccount, private val mainPs4Name: String,
	private val connectivityManager: ConnectivityManager?): ViewModel()
{
	companion object
	{
		private const val TAG = "MainViewModel"
		/** Discovery finds consoles at home in about a second, so PSN doesn't offer them meanwhile */
		private const val LOCAL_SEARCH_MS = 3000L
		private const val PSN_REFRESH_INTERVAL_MS = 60 * 1000L
		private const val PSN_LIST_RETRIES = 2L
		/**
		 * A console drops out of discovery for a few seconds when it changes state, so one seen here
		 * that recently isn't offered through PSN, which made its card flip back and forth.
		 */
		private const val SEEN_HERE_MS = 60 * 1000L
		/** Lets cards appear once a console hasn't been seen for that long */
		private const val PSN_RECHECK_SECONDS = 10L
		/** PSN tells only the main PS4 of an account, which it knows by this id */
		private val MAIN_PS4_UID = ByteArray(32) { 'A'.code.toByte() }
	}

	private val disposable = CompositeDisposable()

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		it.discoveryActive
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe { preferences.discoveryEnabled = it }
			.addTo(disposable)
	}

	/** The consoles of the PSN account, empty if not signed in or listing them failed */
	private val psnDevices = BehaviorSubject.createDefault(Optional.empty<List<HolepunchDevice>>())
	private val localSearchDone = BehaviorSubject.createDefault(false).also { subject ->
		Observable.timer(LOCAL_SEARCH_MS, TimeUnit.MILLISECONDS)
			.subscribe { subject.onNext(true) }
			.addTo(disposable)
	}
	private var psnRefresh: Disposable? = null
	private var psnRefreshedAtMs: Long? = null

	private val _psnSignInExpired = MutableLiveData(false)
	/** PSN wants a new sign-in for playing away from home */
	val psnSignInExpired: LiveData<Boolean> get() = _psnSignInExpired

	/** When discovery last saw each console here, by name and by MAC. Only used by the combiner below. */
	private val seenHere = mutableMapOf<String, Long>()

	val displayHosts = Observables.combineLatest(
			database.manualHostDao().getAll().toObservable(),
			database.registeredHostDao().getAll().toObservable(),
			discoveryManager.discoveredHosts,
			Observables.combineLatest(psnDevices, localSearchDone, Observable.interval(0, PSN_RECHECK_SECONDS, TimeUnit.SECONDS))
				{ devices, done, _ -> if(done) devices else Optional.empty() })
			{ manualHosts, registeredHosts, discoveredHosts, psnDevices ->
				val now = SystemClock.elapsedRealtime()
				discoveredHosts.forEach { host ->
					host.hostName?.let { seenHere[it] = now }
					host.serverMac?.let { seenHere[macKey(it)] = now }
				}
				val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
				val idRegisteredHosts = registeredHosts.associateBy { it.id }
				discoveredHosts.map {
					DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
				} +
				manualHosts.map {
					ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
				} +
				(if(psnDevices.isPresent) psnDisplayHosts(registeredHosts, psnDevices.get(), now) else listOf())
			}
			// The recheck mostly finds nothing new
			.distinctUntilChanged()
			.toLiveData()

	private fun macKey(mac: MacAddress) = "mac:${mac.value}"

	/** Off Wi-Fi and Ethernet, discovery can't find consoles at home, so PSN offers them right away */
	private fun onLocalNetwork(): Boolean
	{
		val manager = connectivityManager ?: return true
		val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
		return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
	}

	val discoveryActive = discoveryManager.discoveryActive.toLiveData()

	val registeredHosts = database.registeredHostDao().getAll().toLiveData()

	/** Whether discovery had the time to find consoles here */
	val isLocalSearchDone get() = localSearchDone.value == true

	/**
	 * The PS5s of the PSN account with Remote Play on that discovery doesn't find here. Connecting
	 * through PSN needs no registration here; one with the same name gives the console its settings.
	 */
	private fun psnDisplayHosts(registeredHosts: List<RegisteredHost>, devices: List<HolepunchDevice>, now: Long): List<PsnDisplayHost>
	{
		val local = onLocalNetwork()
		fun seen(key: String) = local && seenHere[key]?.let { now - it < SEEN_HERE_MS } == true
		// Registering again adds another registration of the same console
		val registered = registeredHosts.sortedByDescending { it.id }.distinctBy { it.serverMac }
		val registeredPS5 = registered.filter { it.target.isPS5 }
		val hosts = devices.filter { it.ps5 && it.remotePlayEnabled }.mapNotNull { device ->
			val registeredHost = registeredPS5.firstOrNull { it.serverNickname == device.asciiName || it.serverNickname == device.name }
			if(seen(device.asciiName) || (registeredHost != null && seen(macKey(registeredHost.serverMac))))
				return@mapNotNull null
			PsnDisplayHost(registeredHost, device.name, device.uid, true)
		}
		val registeredPS4 = registered.filter { !it.target.isPS5 }
		val ps4 = registeredPS4.firstOrNull()
			?.takeIf { registeredPS4.none { seen(macKey(it.serverMac)) } }
			?.let { PsnDisplayHost(it, (if(registeredPS4.size == 1) it.serverNickname else null) ?: mainPs4Name, MAIN_PS4_UID, false) }
		return hosts + listOfNotNull(ps4)
	}

	/**
	 * Lists the consoles of the PSN account again, unless that was just done.
	 * Listing them also shows whether Remote Play is on for each.
	 */
	fun refreshPsnConsoles()
	{
		if(!psnAccount.isSignedIn)
		{
			psnRefresh?.dispose()
			psnRefreshedAtMs = null
			psnDevices.onNext(Optional.empty())
			return
		}
		if(psnRefresh?.isDisposed == false)
			return
		val refreshedAt = psnRefreshedAtMs
		if(refreshedAt != null && SystemClock.elapsedRealtime() - refreshedAt < PSN_REFRESH_INTERVAL_MS)
			return
		psnRefresh = Single.fromCallable { HolepunchDevice.list(psnAccount.accessToken(), true) }
			.retry(PSN_LIST_RETRIES) { it !is PsnAuth.SignInRejectedException }
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe({ devices ->
				psnRefreshedAtMs = SystemClock.elapsedRealtime()
				Log.i(TAG, "Consoles on PSN: ${devices.joinToString { "${it.name} (Remote Play ${if(it.remotePlayEnabled) "on" else "off"})" }}")
				psnDevices.onNext(Optional.of(devices))
			}, { error ->
				Log.e(TAG, "Listing the consoles on PSN failed", error)
				if(error is PsnAuth.SignInRejectedException)
				{
					psnDevices.onNext(Optional.empty())
					_psnSignInExpired.value = true
				}
			})
			.addTo(disposable)
	}

	fun psnSignInExpiredShown()
	{
		_psnSignInExpired.value = false
	}

	/** Removes all registrations of the console, so consoles added by IP address are no longer linked to it */
	fun removeRegistration(registeredHost: RegisteredHost)
	{
		database.registeredHostDao()
			.deleteByMac(registeredHost.serverMac)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		database.manualHostDao()
			.delete(manualHost)
			.onErrorComplete()
			.subscribeOn(Schedulers.io())
			.subscribe()
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		disposable.dispose()
		discoveryManager.dispose()
	}
}
