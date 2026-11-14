package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.EnsNameTokens
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.AccountList
import eth.serotine.domain.repository.AccountRepository
import eth.serotine.domain.repository.NameResolution
import eth.serotine.domain.repository.NameResolver
import eth.serotine.domain.repository.NftContracts
import eth.serotine.domain.repository.NftHolding
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.repository.SeenEnsName
import eth.serotine.domain.repository.SeenEnsNames
import eth.serotine.domain.repository.TokenChange
import kotlinx.coroutines.flow.first

/**
 * A [NameResolver] that remembers each name it resolves to one of the user's accounts (issue #274):
 * [inner] answers, unchanged, and a [NameResolution.Resolved] whose address is an account of
 * [accounts] on that network is kept in [seen] with its block. Every resolution in the wallet goes
 * through it (Watch, the duplicate case included; Send; dApp requests; the check of a followed name),
 * so a name seen anywhere becomes a candidate the account's NFTs are proven against. A name that lands
 * on an address not in the wallet is not kept: the list never records whom the user looked up.
 */
class RememberResolvedNames(
    private val inner: NameResolver,
    private val accounts: AccountRepository,
    private val seen: SeenEnsNames,
) : NameResolver {

    override suspend fun resolve(network: Network, name: String): NameResolution {
        val resolution = inner.resolve(network, name)
        if (resolution is NameResolution.Resolved) remember(network, resolution)
        return resolution
    }

    private suspend fun remember(network: Network, resolved: NameResolution.Resolved) {
        val normal = EnsNameTokens.normalized(resolved.name) ?: return
        val list = accounts.observeAccounts().first() as? AccountList.Loaded ?: return
        val account = AccountId(network, resolved.address)
        if (list.accounts.none { it.id == account }) return
        // Nothing to say if it cannot be kept: the resolution's answer stands either way.
        seen.remember(account, SeenEnsName(normal, resolved.blockNumber))
    }
}

/** What [AddEnsName] did. */
sealed interface EnsNameAddition {

    /** The account was proven to hold the name, and it is kept: its NFTs list it once read. */
    data object Added : EnsNameAddition

    /** It is kept for the account already. */
    data object AlreadyAdded : EnsNameAddition

    /** It is no ENS name ("alice.eth"), or the account's network has none (Gnosis Chain). */
    data object NotAName : EnsNameAddition

    /** Nothing verified says the account holds it: neither the registrar nor the NameWrapper names it. */
    data object NotHeld : EnsNameAddition

    /** No verified answer right now, for [reason]: nothing was kept. */
    data class Unavailable(val reason: ReadUnavailable) : EnsNameAddition

    /** The list could not be saved, or cannot be read: nothing was kept. */
    data object NotSaved : EnsNameAddition
}

/**
 * *Add an NFT* by ENS name (issue #274): the tokens the name can be ([EnsNameTokens.candidates]: the
 * .eth registrar's and the NameWrapper's, both worked out by hashing the name) are proven held or not
 * at the head with [contracts], as `AddNft` proves a contract and id; once one is held, the name is
 * kept in [seen], from where the account's NFTs find it. Nothing is kept on the name's word alone, nor
 * without a verified answer. ENSv2's names (Sepolia) are not found this way ([EnsNameTokens]).
 */
class AddEnsName(private val contracts: NftContracts, private val seen: SeenEnsNames) {

    suspend operator fun invoke(account: AccountId, typed: String): EnsNameAddition {
        if (!account.network.ensNames) return EnsNameAddition.NotAName
        val name = EnsNameTokens.normalized(typed) ?: return EnsNameAddition.NotAName
        val candidates = EnsNameTokens.candidates(account.network, name)
        if (candidates.isEmpty()) return EnsNameAddition.NotAName
        var unavailable: ReadUnavailable? = null
        for (nft in candidates) {
            when (val holding = contracts.holding(account.network, account.address, nft)) {
                is NftHolding.Held -> return when (seen.remember(account, SeenEnsName(name, holding.blockNumber))) {
                    TokenChange.Changed -> EnsNameAddition.Added
                    TokenChange.AlreadyPresent -> EnsNameAddition.AlreadyAdded
                    TokenChange.NotFound, TokenChange.NotSaved -> EnsNameAddition.NotSaved
                }
                is NftHolding.NotHeld -> Unit
                is NftHolding.Unavailable -> unavailable = unavailable ?: holding.reason
            }
        }
        // One token not answered could still be the one the account holds it by.
        return unavailable?.let { EnsNameAddition.Unavailable(it) } ?: EnsNameAddition.NotHeld
    }
}
