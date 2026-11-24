package com.quietwire.quietledger.diagnostic

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quietwire.quietledger.core.crypto.DatabaseKeyStore
import com.quietwire.quietledger.data.db.DatabaseProvider
import com.quietwire.quietledger.data.repository.DebtRepository
import com.quietwire.quietledger.data.repository.HoldingsRepository
import com.quietwire.quietledger.data.repository.MetalsRepository
import com.quietwire.quietledger.data.repository.RecurringExpenseRepository
import com.quietwire.quietledger.data.repository.SavingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the app's own read path against the real database.
 *
 * Read only. The database contents are known to be correct, so if a flow here
 * fails to emit then the fault is in the repository layer, which is what the
 * JVM tests cannot see: they substitute a plain in-memory database and a fake
 * holder for SQLCipher and [DatabaseProvider].
 */
@RunWith(AndroidJUnit4::class)
class ReadPathDiagnostic {

    private val tag = "QLRead"

    private fun <T> check(label: String, flow: Flow<T>) = runBlocking {
        val result = withTimeoutOrNull(4_000) { flow.first() }
        when (result) {
            null -> Log.e(tag, "$label -> NO EMISSION within 4s")
            is Collection<*> -> Log.i(tag, "$label -> emitted ${result.size} items")
            else -> Log.i(tag, "$label -> emitted $result")
        }
    }

    @Test
    fun readThroughRepositories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keyStore = DatabaseKeyStore(context)
        val provider = DatabaseProvider(context, keyStore)

        Log.i(tag, "isOpen before open() = ${provider.isOpen}")

        val key = try {
            keyStore.unlockWithKeystore()
        } catch (t: Throwable) {
            Log.e(tag, "key unwrap failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }

        try {
            provider.open(key)
        } catch (t: Throwable) {
            Log.e(tag, "OPEN FAILED: ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(8).forEach { Log.e(tag, "    at $it") }
            return
        }

        Log.i(tag, "isOpen after open() = ${provider.isOpen}")
        Log.i(tag, "observe().value is null? ${provider.observe().value == null}")

        check("debts.observeOpen", DebtRepository(provider).observeOpen())
        check("savings.observeOpen", SavingsRepository(provider).observeOpen())
        check("savings.observeTotal", SavingsRepository(provider).observeTotal())
        check("holdings.observeAll", HoldingsRepository(provider).observeAll())
        check("holdings.observeTotal", HoldingsRepository(provider).observeTotal())
        check("holdings.observeAsOf", HoldingsRepository(provider).observeAsOf())
        check("metals.observeAll", MetalsRepository(provider).observeAll())
        check("metals.observeTotalCost", MetalsRepository(provider).observeTotalCost())
        check("metals.observeSpotPrices", MetalsRepository(provider).observeSpotPrices())
        check("metals.observeSummaries", MetalsRepository(provider).observeSummaries())
        check("metals.observeMarketValue", MetalsRepository(provider).observeMarketValue())

        // Printed in full, so the figures can be compared with the viewer's
        // rather than with a screenshot taken hours earlier.
        runBlocking {
            withTimeoutOrNull(4_000) { MetalsRepository(provider).observeSummaries().first() }?.forEach {
                Log.i(
                    tag,
                    "METALSUM ${it.metal} oz=${it.ouncesLabel} lots=${it.lots} " +
                        "cost=${it.cost.toPlainString()} " +
                        "market=${it.marketValue?.toPlainString() ?: "none"}"
                )
            }
            withTimeoutOrNull(4_000) { MetalsRepository(provider).observeMarketValue().first() }?.let {
                Log.i(tag, "METALTOTAL market=${it?.toPlainString() ?: "none"}")
            }
            withTimeoutOrNull(4_000) { RecurringExpenseRepository(provider).observeMonthlyTotal().first() }?.let {
                Log.i(tag, "EXPENSEMONTHLY ${it.toPlainString()}")
            }
        }

        // Closed in a finally, because instrumentation runs in the app's own
        // process: a second open database left behind here competes with the
        // app's own, and may well be what produced the failure I then went
        // looking for.
        provider.close()
        Log.i(tag, "done, provider closed")
    }
}
