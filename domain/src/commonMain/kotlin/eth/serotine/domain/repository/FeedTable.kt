package eth.serotine.domain.repository

import eth.serotine.domain.model.Network
import eth.serotine.domain.model.PriceId

/**
 * The port for which prices are pinned where, without reading any: each network's price ids with a
 * feed, and the price id each is quoted in, as [PriceFeeds.quotes] has them once that network's
 * light client runs. :data serves it from its pinned table, so a choice made while no light client
 * runs (a widget being placed) sees every network's.
 */
interface FeedTable {
    /** Each price id with a feed pinned on [network], and the price id its feed quotes in. Fixed. */
    fun quotes(network: Network): Map<PriceId, PriceId>
}
