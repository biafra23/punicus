package eth.serotine.ui.addtoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.repository.MetadataReading
import eth.serotine.domain.repository.ReadUnavailable
import eth.serotine.domain.usecase.AddToken
import eth.serotine.domain.usecase.HoldNetwork
import eth.serotine.domain.usecase.KnownTokenList
import eth.serotine.domain.usecase.NetworkHold
import eth.serotine.domain.usecase.ObserveEnabledNetworks
import eth.serotine.domain.usecase.ObserveKnownTokens
import eth.serotine.domain.usecase.ObserveTokenMetadata
import eth.serotine.domain.usecase.SetNetworkEnabled
import eth.serotine.domain.usecase.TokenAdd
import eth.serotine.ui.components.AddressInput
import eth.serotine.ui.components.Eip55
import eth.serotine.ui.components.NetworkChip
import eth.serotine.ui.components.NetworkLabel
import eth.serotine.ui.components.displayName
import eth.serotine.ui.components.displaySymbol
import eth.serotine.ui.components.grouped
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Add token's ViewModel: reads the typed text as an address (EIP-55 checked at the input),
 * shows what the contract there says about itself, verified, and adds it through [AddToken],
 * which reads it again before it adds. [address] is the text the field starts with: a contract an
 * Activity detail found, opened from there (docs/wallet-ux.md, screen 26); empty from Settings.
 * The network comes first (docs/networks.md, N17): [network] when the screen was opened for one (an
 * Activity's account's), else the first that is on; the contract is read there, its light client
 * held running while the screen is open.
 */
class AddTokenViewModel(
    observeKnownTokens: ObserveKnownTokens,
    observeTokenMetadata: ObserveTokenMetadata,
    private val addToken: AddToken,
    observeEnabledNetworks: ObserveEnabledNetworks,
    private val setNetworkEnabled: SetNetworkEnabled,
    /** Keeps the network chosen running while this screen is open, with no account on it yet. */
    private val holdNetwork: HoldNetwork,
    address: String = "",
    network: Network? = null,
) : ViewModel() {

    /** The network the user, or the screen that opened this one, chose; null: the first that is on. */
    private val picked = MutableStateFlow(network)

    /** The networks on in this wallet; null until read. */
    private val on: StateFlow<List<Network>?> =
        observeEnabledNetworks().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The network the token is read and added on; null while none is on. */
    private val chosen: StateFlow<Network?> = combine(picked, on) { picked, on ->
        picked ?: on?.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, network)

    /** The network whose light client the strip shows: the one chosen. */
    val stripNetwork: StateFlow<Network> = chosen.map { it ?: Network.entries.first() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, network ?: Network.entries.first())

    /** The hold on [chosen], moved with it, released when the screen is gone ([onCleared]). */
    private var hold: NetworkHold? = null

    init {
        viewModelScope.launch {
            chosen.collect { network ->
                hold?.release()
                hold = network?.let { holdNetwork(it) }
            }
        }
    }

    override fun onCleared() {
        hold?.release()
        hold = null
    }

    /** Reads and adds the token on the network of [chainId]. */
    fun onNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        if (outcome.value.adding || outcome.value.done) return
        picked.value = network
        outcome.value = Outcome()
    }

    /** Turns the network of [chainId] on, and chooses it. */
    fun onTurnOnNetwork(chainId: Long) {
        val network = Network.ofChainId(chainId) ?: return
        viewModelScope.launch { if (setNetworkEnabled(network, on = true)) picked.value = network }
    }

    private class Outcome(val adding: Boolean = false, val error: String? = null, val done: Boolean = false)

    private val typed = MutableStateFlow(address)
    private val outcome = MutableStateFlow(Outcome())
    private val input = typed.map { Eip55.parse(it) }.distinctUntilChanged()

    /**
     * The card of the address last typed, with that address. A new address does not wait for
     * the last one's read to end (a verified call can take long): that read is cancelled and
     * left to finish on its own, and a card it might still produce names its own address,
     * which is then not the one typed.
     */
    private val card: Flow<Pair<Address, TokenCard>?> = channelFlow {
        var current: Job? = null
        combine(input, chosen, ::Pair).distinctUntilChanged().collect { (parsed, network) ->
            current?.cancel()
            if (parsed is AddressInput.Valid && network != null) {
                current = launch {
                    combine(
                        observeKnownTokens(network),
                        observeTokenMetadata(network, parsed.address)
                            .map<MetadataReading, MetadataReading?> { it }
                            .onStart { emit(null) },
                    ) { known, reading -> parsed.address to cardOf(parsed.address, known, reading) }
                        .collect { send(it) }
                }
            } else {
                current = null
                send(null)
            }
        }
    }

    val uiState: StateFlow<AddTokenUiState> = combine(
        combine(typed, input, ::Pair),
        card,
        outcome,
        combine(chosen, on, ::Pair),
    ) { (text, parsed), card, outcome, (network, on) ->
        val typedAddress = (parsed as? AddressInput.Valid)?.address
        AddTokenUiState(
            networks = on?.let { chips(network, it) }.orEmpty(),
            address = text,
            addressError = addressError(text, parsed),
            // Only the typed address's card: until it has one, it is being read.
            card = card?.takeIf { (address, _) -> address == typedAddress }?.second
                ?: TokenCard.Unavailable(NO_NETWORK).takeIf { typedAddress != null && on?.isEmpty() == true }
                ?: TokenCard.Checking.takeIf { typedAddress != null },
            adding = outcome.adding,
            error = outcome.error,
            done = outcome.done,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddTokenUiState(address = address))

    /** The address field changed; ignored while an add runs. */
    fun onAddressChange(text: String) {
        if (outcome.value.adding || outcome.value.done) return
        typed.value = text
        outcome.value = Outcome()
    }

    /**
     * Adds the token, once, and only the one whose card is shown: the card must be the typed
     * address's. [AddToken] reads the contract again first.
     */
    fun add() {
        val address = (Eip55.parse(typed.value) as? AddressInput.Valid)?.address ?: return
        val shown = uiState.value.card as? TokenCard.Claim ?: return
        if (Address.parse(shown.address) != address) return
        if (outcome.value.adding || outcome.value.done || !uiState.value.canAdd) return
        val network = chosen.value ?: return
        outcome.value = Outcome(adding = true)
        viewModelScope.launch {
            outcome.value = when (val result = addToken(network, address)) {
                is TokenAdd.Added -> Outcome(done = true)
                is TokenAdd.AlreadyKnown ->
                    Outcome(error = "The wallet knows this token already, as ${displaySymbol(result.token.symbol)}.")
                TokenAdd.NotAToken -> Outcome(error = NOT_A_TOKEN)
                is TokenAdd.NotVerified -> Outcome(error = "The contract cannot be read right now. Nothing was added.")
                TokenAdd.NotSaved -> Outcome(error = "The token could not be saved. Nothing was added.")
            }
        }
    }

    /** Each network as the first choice (N17): one that is off greyed out, with Turn on. */
    private fun chips(chosen: Network?, on: List<Network>): List<NetworkChip> = Network.entries.map { network ->
        val off = network !in on
        NetworkChip(
            NetworkLabel.of(network),
            selected = network == chosen,
            reason = "${network.displayName} is off in this wallet. Turn it on to add a token there.".takeIf { off },
            off = off,
        )
    }

    private fun cardOf(address: Address, known: KnownTokenList, reading: MetadataReading?): TokenCard {
        known.tokens.firstOrNull { it.token.address == address }?.let {
            return TokenCard.Known(displaySymbol(it.token.symbol))
        }
        return when (reading) {
            null, MetadataReading.Syncing -> if (reading == null) TokenCard.Checking else TokenCard.Unavailable(SYNCING)
            MetadataReading.NotAToken -> TokenCard.Unavailable(NOT_A_TOKEN)
            is MetadataReading.Unavailable -> TokenCard.Unavailable(
                when (reading.reason) {
                    ReadUnavailable.NO_ENGINE ->
                        "There is no light client on this device, so the contract cannot be read."
                    ReadUnavailable.NETWORK_OFF ->
                        "The network's light client is not running, so the contract cannot be read."
                    ReadUnavailable.STALE_ANCHOR -> "The light client refuses to sync, so the contract cannot be read."
                    ReadUnavailable.SYNCING -> "The light client is still syncing, so the contract cannot be read yet."
                    ReadUnavailable.UNPROVEN -> "The contract's answers could not be verified this time. Serotine keeps trying."
                    ReadUnavailable.REFUSED -> "The light client on this device cannot read this contract."
                },
            )
            is MetadataReading.Verified -> TokenCard.Claim(
                address = Eip55.format(address),
                symbol = displaySymbol(reading.symbol),
                name = displayName(reading.name),
                decimals = reading.decimals,
                checkedAt = reading.blockNumber?.let { "Read from the contract at block ${grouped(it)}" }
                    ?: "Read from the contract",
                simpleCheckedAt = reading.blockNumber?.let { "Read from the contract, checked on chain" }
                    ?: "Read from the contract",
                warning = known.tokens.firstOrNull { it.token.symbol.equals(reading.symbol, ignoreCase = true) }
                    ?.let {
                        "Not the ${displaySymbol(it.token.symbol)} the wallet lists: another contract calls itself that"
                    },
            )
        }
    }

    private fun addressError(text: String, parsed: AddressInput): String? = when {
        text.isBlank() || parsed is AddressInput.Valid -> null
        parsed is AddressInput.ChecksumMismatch ->
            "The capitals do not match this address's checksum: check it for a typo."
        text.trim().length >= ADDRESS_LENGTH -> "Not an address: an address is 0x and 40 hexadecimal digits."
        else -> null
    }

    private companion object {
        const val ADDRESS_LENGTH = 42
        const val NOT_A_TOKEN = "This address does not answer as a token."
        const val NO_NETWORK = "No network is on in this wallet, so the contract cannot be read. Turn one on first."
        const val SYNCING = "The light client is not synced yet. The contract is read as soon as it is."
    }
}
