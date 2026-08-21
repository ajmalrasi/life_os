package com.lifeos.app

import com.lifeos.app.presentation.DEFAULT_DESTINATION_ROUTE
import com.lifeos.app.presentation.money.MoneySection
import com.lifeos.app.presentation.money.MoneyUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyFirstDefaultsTest {

    @Test
    fun money_overview_is_the_app_and_section_default() {
        assertEquals("money", DEFAULT_DESTINATION_ROUTE)
        assertEquals(MoneySection.OVERVIEW, MoneyUiState().section)
    }
}
