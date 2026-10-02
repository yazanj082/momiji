// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.metallic.chiaki.lib.Target

class RegistViewModel: ViewModel()
{
	enum class ConsoleVersion {
		PS5,
		PS4_GE_8,
		PS4_GE_7,
		PS4_LT_7;

		val isPS5 get() = this == PS5

		companion object
		{
			/** The version that registration with this choice gives the [target] of */
			fun forTarget(target: Target) = when(target)
			{
				Target.PS4_8 -> PS4_LT_7
				Target.PS4_9 -> PS4_GE_7
				Target.PS4_10, Target.PS4_UNKNOWN -> PS4_GE_8
				Target.PS5_1, Target.PS5_UNKNOWN -> PS5
			}
		}
	}

	val ps4Version = MutableLiveData<ConsoleVersion>(ConsoleVersion.PS5)
}