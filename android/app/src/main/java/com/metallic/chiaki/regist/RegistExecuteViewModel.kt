// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.common.AppDatabase
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.ext.toLiveData
import com.metallic.chiaki.lib.*
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.schedulers.Schedulers

class RegistExecuteViewModel(val database: AppDatabase): ViewModel()
{
	enum class State
	{
		IDLE,
		RUNNING,
		STOPPED,
		FAILED,
		SUCCESSFUL,
		SUCCESSFUL_DUPLICATE,
	}

	private val _state = MutableLiveData<State>(State.IDLE)
	val state: LiveData<State> get() = _state

	private val log = ChiakiRxLog(ChiakiLog.Level.ALL.value/* and ChiakiLog.Level.VERBOSE.value.inv()*/)
	private var regist: Regist? = null

	val logText: LiveData<String> = log.logText.toLiveData()

	private val disposable = CompositeDisposable()

	var host: RegistHost? = null
		private set

	private var assignManualHostId: Long? = null
	private var replace = false

	/**
	 * @param replace replace an earlier registration of the console without asking,
	 * for registering it again with another PSN account
	 */
	fun start(info: RegistInfo, assignManualHostId: Long?, replace: Boolean)
	{
		if(regist != null)
			return
		try
		{
			// Set before registration starts, whose result comes from another thread
			this.assignManualHostId = assignManualHostId
			this.replace = replace
			regist = Regist(info, log.log, this::registEvent)
			_state.value = State.RUNNING
		}
		catch(error: CreateError)
		{
			log.log.e("Failed to create Regist: ${error.errorCode}")
			_state.value = State.FAILED
		}
	}

	fun stop()
	{
		regist?.stop()
	}

	private fun registEvent(event: RegistEvent)
	{
		when(event)
		{
			is RegistEventCanceled -> _state.postValue(State.STOPPED)
			is RegistEventFailed -> _state.postValue(State.FAILED)
			is RegistEventSuccess -> registSuccess(event.host)
		}
	}

	private fun registSuccess(host: RegistHost)
	{
		this.host = host
		if(replace)
		{
			saveHost()
			return
		}
		database.registeredHostDao().getByMac(MacAddress(host.serverMac))
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.doOnSuccess {
				_state.value = State.SUCCESSFUL_DUPLICATE
			}
			.doOnComplete {
				saveHost()
			}
			.subscribe()
			.addTo(disposable)
	}

	fun saveHost()
	{
		val host = host ?: return
		val assignManualHostId = assignManualHostId
		Single.fromCallable { database.saveRegisteredHost(RegisteredHost(host), assignManualHostId) }
			.subscribeOn(Schedulers.io())
			.observeOn(AndroidSchedulers.mainThread())
			.subscribe({
				Log.i("RegistExecute", "Registered Host saved in db")
				_state.value = State.SUCCESSFUL
			}, {
				Log.e("RegistExecute", "Failed to save Registered Host", it)
				_state.value = State.FAILED
			})
			.addTo(disposable)
	}

	override fun onCleared()
	{
		super.onCleared()
		regist?.dispose()
		disposable.dispose()
	}
}