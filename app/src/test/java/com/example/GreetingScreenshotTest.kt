package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.local.AppTradeEntity
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun greeting_screenshot() {
    val samplePendingTrade = AppTradeEntity(
        symbol = "SOL",
        entryPrice = 188.50,
        targetExitPrice = 193.00,
        investedUsdt = 30.0,
        coinAmount = 0.1588,
        status = "PENDING_BUY",
        ambushTimeoutMinutes = 45
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        PendingAmbushCard(
            trade = samplePendingTrade,
            currentPrice = 189.20,
            onRequestConfirmFill = {},
            onCancelAmbush = {},
            onExtendTimeout = {}
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}
