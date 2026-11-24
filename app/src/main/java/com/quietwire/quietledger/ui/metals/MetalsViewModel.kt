package com.quietwire.quietledger.ui.metals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietwire.quietledger.core.money.Money
import com.quietwire.quietledger.data.repository.MetalsRepository
import com.quietwire.quietledger.domain.model.MetalLot
import com.quietwire.quietledger.domain.model.Metal
import com.quietwire.quietledger.domain.model.MetalSummary
import com.quietwire.quietledger.ui.common.resilient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MetalsUiState(
    val summaries: List<MetalSummary> = emptyList(),
    val lots: List<MetalLot> = emptyList(),
    val totalCost: Money = Money.ZERO,
    /** Null until every held metal has a price entered. */
    val marketValue: Money? = null,
    val loading: Boolean = true,
    val unavailable: Boolean = false
) {
    /** Gain against everything paid, or null without a complete valuation. */
    val gain: Money? get() = marketValue?.let { it - totalCost }

    /** Metals held that still have no price, so the UI can name them. */
    val awaitingPrice: List<MetalSummary> get() = summaries.filter { it.spot == null }

    /** Oldest entered price, which is how current the valuation really is. */
    val oldestPriceAgeDays: Long?
        get() = summaries.mapNotNull { it.spot?.ageInDays() }.maxOrNull()

    val vendors: List<String> get() = lots.map { it.vendor }.distinct().sorted()

    /**
     * The current spot for a metal, per troy ounce, if one has been entered.
     *
     * Converted from however it was quoted, so copper's per-pound price becomes
     * comparable with a per-ounce purchase price.
     */
    fun currentSpot(metal: Metal): Money? =
        summaries.firstOrNull { it.metal == metal }?.spot?.perTroyOunce

    /** Shipping across every lot, which is a real part of the cost basis. */
    val shippingTotal: Money
        get() = lots.fold(Money.ZERO) { a, l -> a + l.shipping }
}

data class SpotPriceEditorState(
    val entries: Map<Metal, String> = emptyMap()
) {
    fun parsed(metal: Metal): Money? =
        entries[metal]?.trim()?.takeIf { it.isNotEmpty() }?.let { Money.parseOrNull(it) }

    fun error(metal: Metal): String? {
        val raw = entries[metal]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = Money.parseOrNull(raw) ?: return "That is not an amount"
        return if (!value.isPositive) "Has to be more than nothing" else null
    }

    /** Valid when nothing is malformed. Leaving a metal blank is allowed. */
    val isValid: Boolean get() = Metal.entries.none { error(it) != null }

    val hasAnything: Boolean get() = Metal.entries.any { parsed(it) != null }
}

@HiltViewModel
class MetalsViewModel @Inject constructor(
    private val repository: MetalsRepository
) : ViewModel() {

    val uiState: StateFlow<MetalsUiState> = combine(
        repository.observeSummaries(),
        repository.observeAll(),
        repository.observeTotalCost(),
        repository.observeMarketValue()
    ) { summaries, lots, total, market ->
        MetalsUiState(
            summaries = summaries,
            lots = lots,
            totalCost = total,
            marketValue = market,
            loading = false
        )
    }
        .resilient(MetalsUiState(loading = false, unavailable = true))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MetalsUiState())

    private val _spotEditor = MutableStateFlow<SpotPriceEditorState?>(null)
    val spotEditor: StateFlow<SpotPriceEditorState?> = _spotEditor.asStateFlow()

    /** Opens the editor pre-filled with whatever was last entered. */
    fun startEditingSpotPrices() {
        _spotEditor.value = SpotPriceEditorState(
            entries = uiState.value.summaries
                .mapNotNull { summary ->
                    // Shown exactly as typed, because that is how it is stored.
                    summary.spot?.let { it.metal to it.quotedPrice.toPlainString() }
                }
                .toMap()
        )
    }

    fun updateSpotEditor(metal: Metal, value: String) {
        _spotEditor.value = _spotEditor.value?.let {
            it.copy(entries = it.entries + (metal to value))
        }
    }

    fun cancelSpotEditing() {
        _spotEditor.value = null
    }

    fun saveSpotPrices() {
        val form = _spotEditor.value ?: return
        if (!form.isValid) return

        viewModelScope.launch {
            Metal.entries.forEach { metal ->
                val price = form.parsed(metal)
                // A cleared field removes the price rather than keeping a stale
                // one, so the valuation goes back to being honestly incomplete.
                if (price != null) {
                    // Stored in the unit it was quoted in, and converted only
                    // when a holding is valued.
                    repository.setSpotPrice(metal, price)
                } else {
                    repository.clearSpotPrice(metal)
                }
            }
            _spotEditor.value = null
        }
    }
}
