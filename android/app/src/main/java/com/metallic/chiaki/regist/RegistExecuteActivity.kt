// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.ActivityRegistExecuteBinding
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.main.MainActivity
import com.metallic.chiaki.common.ext.fitSystemBars
import kotlin.math.max

class RegistExecuteActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_REGIST_INFO = "regist_info"
		const val EXTRA_ASSIGN_MANUAL_HOST_ID = "assign_manual_host_id"

		const val RESULT_FAILED = Activity.RESULT_FIRST_USER
	}

	private lateinit var viewModel: RegistExecuteViewModel
	private lateinit var binding: ActivityRegistExecuteBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityRegistExecuteBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.fitSystemBars()

		viewModel = ViewModelProvider(this, viewModelFactory { RegistExecuteViewModel(getDatabase(this)) })
			.get(RegistExecuteViewModel::class.java)

		binding.logTextView.setHorizontallyScrolling(true)
		binding.logTextView.movementMethod = ScrollingMovementMethod()
		viewModel.logText.observe(this, Observer {
			binding.logTextView.text = it
			binding.logTextView.post {
				val textLayout = binding.logTextView.layout ?: return@post
				val scrollY = textLayout.getLineBottom(textLayout.lineCount - 1) - binding.logTextView.height + binding.logTextView.paddingTop + binding.logTextView.paddingBottom
				binding.logTextView.scrollTo(0, max(scrollY, 0))
			}
		})

		binding.detailsButton.setOnClickListener {
			val show = !binding.detailsLayout.isVisible
			binding.detailsLayout.isVisible = show
			binding.detailsButton.setText(if(show) R.string.action_hide_details else R.string.action_show_details)
		}

		viewModel.state.observe(this, Observer {
			val running = it == RegistExecuteViewModel.State.IDLE || it == RegistExecuteViewModel.State.RUNNING
			binding.progressBar.isVisible = running
			binding.resultIconImageView.isVisible = !running
			when(it)
			{
				RegistExecuteViewModel.State.FAILED ->
				{
					showResult(R.drawable.ic_error, R.color.md_error, getString(R.string.regist_failed_title), R.string.regist_failed_info)
					binding.primaryButton.isVisible = true
					binding.primaryButton.setText(R.string.action_try_again)
					binding.primaryButton.setOnClickListener { finish() }
					binding.secondaryButton.isVisible = false
					setResult(RESULT_FAILED)
				}
				RegistExecuteViewModel.State.SUCCESSFUL, RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE ->
				{
					val name = viewModel.host?.serverNickname
					showResult(R.drawable.ic_check_circle, R.color.state_ready,
						if(name != null) getString(R.string.regist_success_title_named, name) else getString(R.string.regist_success_title),
						R.string.regist_success_info)
					binding.primaryButton.isVisible = true
					binding.primaryButton.setText(R.string.action_start_playing)
					binding.primaryButton.setIconResource(R.drawable.ic_play)
					binding.primaryButton.setOnClickListener { backToConsoles(connect = true) }
					binding.secondaryButton.isVisible = true
					binding.secondaryButton.setText(R.string.action_done)
					binding.secondaryButton.setOnClickListener { backToConsoles(connect = false) }
					binding.primaryButton.requestFocus()
					setResult(RESULT_OK)
					if(it == RegistExecuteViewModel.State.SUCCESSFUL_DUPLICATE)
						showDuplicateDialog()
				}
				RegistExecuteViewModel.State.STOPPED -> setResult(Activity.RESULT_CANCELED)
				else -> {}
			}
		})

		binding.shareLogButton.setOnClickListener {
			val log = viewModel.logText.value ?: ""
			Intent(Intent.ACTION_SEND).also {
				it.type = "text/plain"
				it.putExtra(Intent.EXTRA_TEXT, log)
				startActivity(Intent.createChooser(it, resources.getString(R.string.action_share_log)))
			}
		}

		val registInfo = intent.getParcelableExtra<RegistInfo>(EXTRA_REGIST_INFO)
		if(registInfo == null)
		{
			finish()
			return
		}
		viewModel.start(registInfo,
			if(intent.hasExtra(EXTRA_ASSIGN_MANUAL_HOST_ID))
				intent.getLongExtra(EXTRA_ASSIGN_MANUAL_HOST_ID, 0)
			else
				null)
	}

	private fun showResult(icon: Int, color: Int, title: String, info: Int)
	{
		binding.resultIconImageView.setImageResource(icon)
		binding.resultIconImageView.imageTintList = ContextCompat.getColorStateList(this, color)
		binding.titleTextView.text = title
		binding.infoTextView.setText(info)
	}

	/**
	 * Back to the consoles, closing registration, and optionally connecting to the registered console right away
	 */
	private fun backToConsoles(connect: Boolean)
	{
		Intent(this, MainActivity::class.java).also {
			it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
			val mac = viewModel.host?.serverMac
			if(connect && mac != null)
				it.putExtra(MainActivity.EXTRA_CONNECT_HOST_MAC, MacAddress(mac).value)
			startActivity(it)
		}
		finish()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.stop()
	}

	private var dialog: AlertDialog? = null

	private fun showDuplicateDialog()
	{
		if(dialog != null)
			return

		val macStr = viewModel.host?.serverMac?.let { MacAddress(it).toString() } ?: ""

		dialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_regist_duplicate, macStr))
			.setNegativeButton(R.string.action_regist_discard) { _, _ ->  }
			.setPositiveButton(R.string.action_regist_overwrite) { _, _ ->
				viewModel.saveHost()
			}
			.create()
			.also { it.show() }

	}
}