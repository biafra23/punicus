package eth.serotine.domain.usecase

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.SwapVenue
import eth.serotine.domain.repository.LightClient
import eth.serotine.domain.repository.SwapVenueSetting
import eth.serotine.domain.repository.UniswapV3Chain
import eth.serotine.domain.repository.VenueChoice
import eth.serotine.domain.repository.VenueReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The venue a swap starts at on Swap, compose (22; docs/swaps.md, *The user's choice of venue*):
 * the one the user last chose, and Uniswap the first time, or while the stored choice cannot be
 * read (owner ruling, 2026-10-22, decision 10). Still the user's to change before anything is
 * signed.
 */
class ObserveSwapVenue(private val setting: SwapVenueSetting) {
    operator fun invoke(): Flow<SwapVenue> =
        setting.observe().map { choice -> (choice as? VenueChoice.Chosen)?.venue ?: SwapVenue.UNISWAP }
}

/** Keeps [SwapVenue] as the one the next swap starts at; false when it could not be saved. */
class ChooseSwapVenue(private val setting: SwapVenueSetting) {
    suspend operator fun invoke(venue: SwapVenue): Boolean = setting.choose(venue)
}

/**
 * Whether a CoW order can be offered on this device at all ([LightClient.datesFinalizedBlocks]), so
 * that Swap can grey CoW out before anything is asked (docs/swaps.md, *The user's choice of venue*).
 */
class CowOfferedOnThisDevice(private val lightClient: LightClient) {
    operator fun invoke(): Boolean = lightClient.datesFinalizedBlocks
}

/**
 * Whether Swap is offered on a network: false only where no Uniswap v3 deployment is pinned
 * ([VenueReading.NotPinned]), as on Gnosis Chain until one is (owner ruling, 2026-10-22;
 * docs/networks.md, open question 7), so that the account screen greys Swap out with the reason.
 * A pinned deployment not proven right now still offers Swap, whose quote proves it or says why
 * it cannot.
 */
class HasSwapVenue(private val uniswap: UniswapV3Chain) {
    suspend operator fun invoke(network: Network): Boolean = uniswap.venue(network) != VenueReading.NotPinned
}
