package eth.serotine.data.engine

import eth.serotine.domain.model.Address
import eth.serotine.domain.model.Network
import eth.serotine.domain.model.UInt256
import eth.serotine.domain.repository.BlockReading
import eth.serotine.domain.repository.ChainTransaction
import eth.serotine.domain.repository.HeadReading
import eth.serotine.domain.repository.ReadUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class EngineBlockTransactionsTest {

    private val chain = FakeTransactingChain()

    private fun TestScope.blocks(chain: TransactingChain? = this@EngineBlockTransactionsTest.chain) =
        EngineBlockTransactions(Network.SEPOLIA, chain, StandardTestDispatcher(testScheduler))

    private val body = """
        {"number":"0x65","hash":"0x${"f".repeat(64)}","transactions":[
          {"hash":"0x${"A".repeat(64)}","from":"0x1111111111111111111111111111111111111111",
           "to":"0x2222222222222222222222222222222222222222","value":"0x3e8","input":"0x"},
          {"hash":"0x${"b".repeat(64)}","from":"0x1111111111111111111111111111111111111111",
           "to":null,"value":"0x0","input":"0x6080"}
        ]}
    """.trimIndent()

    @Test
    fun theHeadIsTheOneReadsCanBeVerifiedAt() = runTest {
        chain.base.snapshot = running(head = 101)
        assertEquals(HeadReading.At(101), blocks().head(Network.SEPOLIA))
    }

    @Test
    fun aBlockIsItsVerifiedTransactionsInOrder() = runTest {
        chain.base.snapshot = running(head = 101)
        chain.bodies[101] = body
        val reading = blocks().transactions(Network.SEPOLIA, 101)
        assertEquals(
            BlockReading.Read(
                listOf(
                    ChainTransaction(
                        "0x" + "a".repeat(64),
                        Address.of("0x1111111111111111111111111111111111111111"),
                        Address.of("0x2222222222222222222222222222222222222222"),
                        UInt256.of(1_000L),
                        "0x",
                    ),
                    ChainTransaction(
                        "0x" + "b".repeat(64),
                        Address.of("0x1111111111111111111111111111111111111111"),
                        null,
                        UInt256.of(0L),
                        "0x6080",
                    ),
                ),
            ),
            reading,
        )
    }

    @Test
    fun aBlockAboveTheHeadIsNotYetAndNotAskedFor() = runTest {
        chain.base.snapshot = running(head = 100)
        assertEquals(BlockReading.NotYet, blocks().transactions(Network.SEPOLIA, 101))
        assertTrue(chain.bodiesAsked.isEmpty())
    }

    @Test
    fun aBlockTheEngineDoesNotHoldYetIsNotYet() = runTest {
        chain.base.snapshot = running(head = 101)
        chain.bodies[101] = "null"
        assertEquals(BlockReading.NotYet, blocks().transactions(Network.SEPOLIA, 101))
    }

    @Test
    fun noAnswerAnotherBlockOrAMalformedOneIsUnproven() = runTest {
        chain.base.snapshot = running(head = 101)
        val unproven = BlockReading.Unavailable(ReadUnavailable.UNPROVEN)
        assertEquals(unproven, blocks().transactions(Network.SEPOLIA, 101), "no answer")
        chain.bodies[101] = body.replace("\"0x65\"", "\"0x64\"")
        assertEquals(unproven, blocks().transactions(Network.SEPOLIA, 101), "the block before")
        chain.bodies[101] = body.replace("\"value\":\"0x3e8\",", "")
        assertEquals(unproven, blocks().transactions(Network.SEPOLIA, 101), "a transaction without its value")
        chain.bodies[101] = """{"number":"0x65","transactions":["0x${"a".repeat(64)}"]}"""
        assertEquals(unproven, blocks().transactions(Network.SEPOLIA, 101), "hashes, not bodies")
    }

    @Test
    fun aLightClientThatCannotVerifyReadsSaysWhy() = runTest {
        chain.base.snapshot = EngineSnapshot.Booting
        assertEquals(HeadReading.Unavailable(ReadUnavailable.SYNCING), blocks().head(Network.SEPOLIA))
        assertEquals(BlockReading.Unavailable(ReadUnavailable.SYNCING), blocks().transactions(Network.SEPOLIA, 1))
    }

    @Test
    fun anotherNetworkOrNoEngineIsRefused() = runTest {
        chain.base.snapshot = running(head = 101)
        val noEngine = HeadReading.Unavailable(ReadUnavailable.NO_ENGINE)
        assertEquals(noEngine, blocks().head(Network.ETHEREUM))
        assertEquals(noEngine, blocks(chain = null).head(Network.SEPOLIA))
    }
}
