package com.lifeos.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lifeos.app.MainActivity
import com.lifeos.app.R
import com.lifeos.app.domain.repository.MoneyRepository
import com.lifeos.app.notifications.AppNotifications
import com.lifeos.app.presentation.money.filterEmiTransactions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpendWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moneyRepository: MoneyRepository,
) {
    suspend fun updateAll() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val today = LocalDate.now()
        val transactions = moneyRepository.observeTransactions(40).first()
        // Match the Money screen's default (EMI excluded) so the widget and the
        // app never disagree on "today"/"this month" spend at a glance.
        val spend = filterEmiTransactions(
            transactions.filter {
                it.direction == "debit" && it.spendCategory != null && it.currency == "INR"
            },
            includeEmi = false,
        )
        val todayTotal = spend
            .filter { it.txnDate == today }
            .fold(BigDecimal.ZERO) { total, transaction -> total + transaction.amount }
        val monthTotal = spend
            .filter { it.txnDate.year == today.year && it.txnDate.month == today.month }
            .fold(BigDecimal.ZERO) { total, transaction -> total + transaction.amount }

        val openMoney = PendingIntent.getActivity(
            context,
            301,
            Intent(context, MainActivity::class.java)
                .putExtra(AppNotifications.EXTRA_OPEN_TAB, "money")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_spend).apply {
                setTextViewText(R.id.widget_today_amount, todayTotal.rupees())
                setTextViewText(
                    R.id.widget_month_label,
                    today.month.name.take(3),
                )
                setTextViewText(R.id.widget_month_amount, monthTotal.rupees())
                setOnClickPendingIntent(R.id.widget_root, openMoney)
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun BigDecimal.rupees(): String {
        val whole = setScale(0, RoundingMode.HALF_UP).toPlainString()
        val negative = whole.startsWith("-")
        val digits = if (negative) whole.drop(1) else whole
        val grouped = if (digits.length <= 3) digits else {
            val tail = digits.takeLast(3)
            val head = digits.dropLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }
        return (if (negative) "-₹" else "₹") + grouped
    }
}
