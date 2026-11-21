package eth.serotine.ui

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.AppHost
import eth.serotine.domain.model.AppIsolation
import eth.serotine.domain.model.WalletPassword
import eth.serotine.domain.repository.DappSiteMemory
import eth.serotine.domain.repository.KeyProtection
import eth.serotine.domain.repository.PerAppDerivation
import eth.serotine.domain.repository.PerAppAliases
import eth.serotine.domain.repository.PerAppKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [DappSiteMemory] in memory. */
internal class MemoryDappSites : DappSiteMemory {
    val remembered = MutableStateFlow<Map<AppHost, List<AccountId>>>(emptyMap())

    override fun observe(): Flow<Map<AppHost, List<AccountId>>> = remembered

    override suspend fun remember(site: AppHost, accounts: List<AccountId>): Boolean {
        remembered.value = remembered.value + (site to accounts)
        return true
    }

    override suspend fun forget(account: AccountId): Boolean {
        remembered.value = remembered.value.mapValues { (_, kept) -> kept - account }.filterValues { it.isNotEmpty() }
        return true
    }
}

/**
 * A [PerAppKeys] that knows [phrases] and makes [address] for any site from [password], each call recorded in
 * [derived]; sealed at a lighter cost than the device's own when [steppedDown].
 */
internal class FakePerAppKeys(
    var phrases: Map<Address, Address> = emptyMap(),
    var address: Address = Address.of("0x" + "a1".repeat(20)),
    private val password: String = "correct horse",
    var steppedDown: Boolean = false,
) : PerAppKeys {
    val derived = mutableListOf<Pair<Address, AppHost>>()

    override suspend fun derivePerApp(from: Address, host: AppHost, password: WalletPassword): PerAppDerivation {
        derived += from to host
        if (password.text != this.password) return PerAppDerivation.WrongPassword
        val phrase = phrases[from] ?: from
        return PerAppDerivation.Stored(address, AppIsolation.pathOf(host), phrase, KeyProtection.HARDWARE, steppedDown)
    }

    override suspend fun phrases(): Map<Address, Address> = phrases
}

/** [PerAppAliases] in memory, which can be made to [failSaves]. */
internal class MemoryAliases(initial: Map<AppHost, Address> = emptyMap()) : PerAppAliases {
    val linked = MutableStateFlow(initial)
    var failSaves = false

    override fun observe(): Flow<Map<AppHost, Address>> = linked

    override suspend fun link(site: AppHost, address: Address): Boolean {
        if (failSaves) return false
        linked.value = linked.value + (site to address)
        return true
    }

    override suspend fun unlink(site: AppHost): Boolean {
        if (failSaves) return false
        linked.value = linked.value - site
        return true
    }
}
