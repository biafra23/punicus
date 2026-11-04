package eth.serotine.domain.usecase

import eth.serotine.domain.model.AccountId
import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network

/** This address as an account on Sepolia, the network these tests are on. */
internal val Address.onSepolia: AccountId
    get() = AccountId(Network.SEPOLIA, this)
