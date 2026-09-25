package com.lifeos.app.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackBarRuleTest {
    @Test
    fun money_is_home_so_back_there_leaves_the_app() {
        assertFalse(isOtherAreaRoot(Destination.MONEY.route))
    }

    @Test
    fun back_on_any_other_area_returns_to_money() {
        Destination.entries.filter { it != Destination.MONEY }.forEach {
            assertTrue(it.route, isOtherAreaRoot(it.route))
        }
    }

    @Test
    fun detail_pages_pop_normally() {
        assertFalse(isOtherAreaRoot("statement/{statementId}"))
        assertFalse(isOtherAreaRoot(SETTINGS_MONEY_ROUTE))
        assertFalse(isOtherAreaRoot(null))
    }
}
