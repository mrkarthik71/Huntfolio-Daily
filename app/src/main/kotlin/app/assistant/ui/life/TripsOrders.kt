package app.assistant.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.assistant.R
import app.assistant.domain.engine.life.OrderRules
import app.assistant.domain.engine.life.TripRules
import app.assistant.domain.model.NotificationType
import app.assistant.domain.model.Order
import app.assistant.domain.model.OrderStatus
import app.assistant.domain.model.Trip
import app.assistant.domain.model.TripKind
import app.assistant.domain.port.PersonalStore
import app.assistant.domain.port.TimeSource
import app.assistant.ui.common.formatDate
import app.assistant.ui.designsystem.Caption
import app.assistant.ui.designsystem.KeyButton
import app.assistant.ui.designsystem.consoleGround
import app.assistant.ui.designsystem.enterAnimated
import app.assistant.ui.designsystem.raisedPanel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(notify: NotifySwitch, private val store: PersonalStore, private val time: TimeSource) :
    LifeViewModel(notify, NotificationType.TRAVEL) {
    val trips: StateFlow<List<Trip>> = store.trips().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val zone: ZoneId get() = time.zone()

    fun upcoming(all: List<Trip>) = TripRules.upcoming(all, time.now(), time.zone())

    fun delete(id: Long) = replanAfter { store.deleteTrip(id) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@HiltViewModel
class OrdersViewModel @Inject constructor(notify: NotifySwitch, private val store: PersonalStore, private val time: TimeSource) :
    LifeViewModel(notify, NotificationType.DELIVERY) {
    val orders: StateFlow<List<Order>> = store.orders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun active(all: List<Order>) = OrderRules.active(all, time.today())

    fun delete(id: Long) = replanAfter { store.deleteOrder(id) }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** The People screen's reminder switch lives in its own small view model so the screen stays simple. */
@HiltViewModel
class TouchNotifyViewModel @Inject constructor(notify: NotifySwitch) : LifeViewModel(notify, NotificationType.KEEP_IN_TOUCH)

@Composable
private fun Frame(caption: String, title: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().consoleGround(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onClick = onBack)
                Caption(caption, Modifier.padding(start = 14.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
        item {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.enterAnimated(0))
        }
        content()
    }
}

@Composable
fun TripsScreen(onBack: () -> Unit, viewModel: TripsViewModel = hiltViewModel()) {
    val all by viewModel.trips.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    val trips = viewModel.upcoming(all)
    val zone = viewModel.zone
    val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    Frame(stringResource(R.string.trips_caption), stringResource(R.string.trips_title), onBack) {
        item { NotifyToggle(notify, stringResource(R.string.trips_notify), stringResource(R.string.trips_notify_reason), viewModel::setNotify) }
        if (trips.isEmpty()) {
            item {
                Text(stringResource(R.string.trips_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(trips, key = { it.id }) { t ->
            val start = t.startsAt.atZone(zone)
            Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(
                    stringResource(kindLabel(t.kind)) + " · " + formatDate(start.toLocalDate()) +
                        (if (TripRules.hasTime(t, zone)) " · " + clock.format(start) else ""),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(t.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                if (t.from != null &&
                    t.to != null
                ) {
                    Text("${t.from} → ${t.to}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                t.ref?.let {
                    Text(
                        stringResource(R.string.trip_notif_ref, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.delete(t.id) }) { Text(stringResource(R.string.life_delete)) }
            }
        }
    }
}

private fun kindLabel(k: TripKind) = when (k) {
    TripKind.TRAIN -> R.string.trip_train
    TripKind.FLIGHT -> R.string.trip_flight
    TripKind.BUS -> R.string.trip_bus
    TripKind.HOTEL -> R.string.trip_hotel
}

@Composable
fun OrdersScreen(onBack: () -> Unit, viewModel: OrdersViewModel = hiltViewModel()) {
    val all by viewModel.orders.collectAsStateWithLifecycle()
    val notify by viewModel.notifyOn.collectAsStateWithLifecycle()
    val orders = viewModel.active(all)
    Frame(stringResource(R.string.orders_caption), stringResource(R.string.orders_title), onBack) {
        item { NotifyToggle(notify, stringResource(R.string.orders_notify), stringResource(R.string.orders_notify_reason), viewModel::setNotify) }
        if (orders.isEmpty()) {
            item {
                Text(stringResource(R.string.orders_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(orders, key = { it.id }) { o ->
            Column(Modifier.fillMaxWidth().raisedPanel(MaterialTheme.shapes.medium).padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Caption(
                    stringResource(statusLabel(o.status)),
                    color = if (o.status ==
                        OrderStatus.DELIVERED
                    ) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(o.merchant, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    stringResource(R.string.order_notif_ref, o.orderRef),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                o.expectedOn?.takeIf { o.status != OrderStatus.DELIVERED }?.let {
                    Text(
                        stringResource(R.string.orders_expected, formatDate(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                o.returnBy?.let {
                    Text(
                        stringResource(R.string.orders_return_by, formatDate(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = { viewModel.delete(o.id) }) { Text(stringResource(R.string.life_delete)) }
            }
        }
    }
}

private fun statusLabel(s: OrderStatus) = when (s) {
    OrderStatus.ORDERED -> R.string.order_ordered
    OrderStatus.SHIPPED -> R.string.order_shipped
    OrderStatus.OUT_FOR_DELIVERY -> R.string.order_out
    OrderStatus.DELIVERED -> R.string.order_delivered
    OrderStatus.CANCELLED -> R.string.order_cancelled
}

/** The People screen's "remind me" switch. */
@Composable
fun TouchNotify(viewModel: TouchNotifyViewModel = hiltViewModel()) {
    val on by viewModel.notifyOn.collectAsStateWithLifecycle()
    NotifyToggle(on, stringResource(R.string.touch_notify), stringResource(R.string.touch_notify_reason), viewModel::setNotify)
}
