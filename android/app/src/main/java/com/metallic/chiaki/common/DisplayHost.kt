// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.lib.DiscoveryHost

sealed class DisplayHost
{
	abstract val registeredHost: RegisteredHost?
	abstract val host: String
	abstract val name: String?
	abstract val id: String?
	abstract val isPS5: Boolean

	val isRegistered get() = registeredHost != null
}

class DiscoveredDisplayHost(
	override val registeredHost: RegisteredHost?,
	val discoveredHost: DiscoveryHost
): DisplayHost()
{
	override val host get() = discoveredHost.hostAddr ?: ""
	override val name get() = discoveredHost.hostName ?: registeredHost?.serverNickname
	override val id get() = discoveredHost.hostId ?: registeredHost?.serverMac?.toString()
	override val isPS5 get() = discoveredHost.isPS5

	override fun equals(other: Any?): Boolean =
		if(other !is DiscoveredDisplayHost)
			false
		else
			other.discoveredHost == discoveredHost && other.registeredHost == registeredHost

	override fun hashCode() = 31 * (registeredHost?.hashCode() ?: 0) + discoveredHost.hashCode()

	override fun toString() = "DiscoveredDisplayHost{${registeredHost}, ${discoveredHost}}"
}

class ManualDisplayHost(
	override val registeredHost: RegisteredHost?,
	val manualHost: ManualHost
): DisplayHost()
{
	override val host get() = manualHost.host
	override val name get() = registeredHost?.serverNickname
	override val id get() = registeredHost?.serverMac?.toString()
	override val isPS5: Boolean get() = registeredHost?.target?.isPS5 ?: false

	override fun equals(other: Any?): Boolean =
		if(other !is ManualDisplayHost)
			false
		else
			other.manualHost == manualHost && other.registeredHost == registeredHost

	override fun hashCode() = 31 * (registeredHost?.hashCode() ?: 0) + manualHost.hashCode()

	override fun toString() = "ManualDisplayHost{${registeredHost}, ${manualHost}}"
}
/**
 * A registered console that isn't on this network, which PSN can connect to.
 * @param registeredHost its registration here. For a PS4, PSN can only reach the account's main PS4.
 */
class PsnDisplayHost(
	override val registeredHost: RegisteredHost,
	val consoleName: String,
	/** The console's id on PSN, 32 bytes */
	val consoleUid: ByteArray,
	override val isPS5: Boolean
): DisplayHost()
{
	/** Not reachable by address */
	override val host get() = ""
	override val name get() = consoleName
	override val id get() = "psn:" + consoleUid.joinToString("") { "%02x".format(it) }

	override fun equals(other: Any?): Boolean =
		if(other !is PsnDisplayHost)
			false
		else
			other.consoleUid.contentEquals(consoleUid) && other.registeredHost == registeredHost && other.consoleName == consoleName

	override fun hashCode() = 31 * registeredHost.hashCode() + consoleUid.contentHashCode()

	override fun toString() = "PsnDisplayHost{${registeredHost}, $consoleName}"
}
