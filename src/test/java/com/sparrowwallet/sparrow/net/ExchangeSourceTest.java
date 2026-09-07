package com.sparrowwallet.sparrow.net;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.List;

/**
 * The fiat estimate is BTCB2's own price, not BTC's.
 *
 * <p>These cover the arithmetic and the refusals. What they cannot cover is the two network calls
 * that feed them, so the numbers used here are the ones the live markets carried when this was
 * written: BTCB2 at $700 on Neoxa, BTC at $79,857 elsewhere.
 */
public class ExchangeSourceTest {
    /** Coingecko's BTC quotes on the day, used only as a pair to divide. */
    private static final double BTC_USD = 79857.32d;
    private static final double BTC_EUR = 73400.00d;

    private static final double BTCB2_USD = 700.00d;

    @Test
    public void theOnlyChoicesAreTheChainsOwnMarketAndNone() {
        List<ExchangeSource> sources = Arrays.asList(ExchangeSource.values());
        Assertions.assertEquals(List.of(ExchangeSource.NONE, ExchangeSource.NEOXA), sources,
                "the BTC sources price the wrong asset and must not be selectable");
    }

    @Test
    public void noneQuotesNothing() {
        Assertions.assertNull(ExchangeSource.NONE.getExchangeRate(Currency.getInstance("USD")));
        Assertions.assertEquals(Collections.emptyList(), ExchangeSource.NONE.getSupportedCurrencies());
    }

    @Test
    public void aDollarPriceIsCarriedIntoAnotherCurrencyByTheRatioOfTwoBtcQuotes() {
        Double eur = ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, BTC_EUR);
        Assertions.assertNotNull(eur);
        Assertions.assertEquals(BTCB2_USD * (BTC_EUR / BTC_USD), eur, 1e-9);
        //Sanity: a euro is worth a bit more than a dollar here, so the euro figure is the smaller.
        Assertions.assertTrue(eur < BTCB2_USD);
    }

    /**
     * The property that makes this construction safe: BTC cancels.
     *
     * <p>Coingecko is quoting the SHA256 chain, which is the very thing this wallet must not price
     * itself against. Scaling both of its quotes by any factor at all leaves the answer untouched,
     * so however wrong or stale that BTC price is, it cannot reach the result.
     */
    @Test
    public void theBtcPriceCancelsSoItCannotContaminateTheResult() {
        Double atRealBtcPrice = ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, BTC_EUR);
        Double atAbsurdBtcPrice = ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD * 1000d, BTC_EUR * 1000d);
        Double atCollapsedBtcPrice = ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD / 5000d, BTC_EUR / 5000d);

        Assertions.assertEquals(atRealBtcPrice, atAbsurdBtcPrice, 1e-9);
        Assertions.assertEquals(atRealBtcPrice, atCollapsedBtcPrice, 1e-9);
    }

    @Test
    public void convertingIntoDollarsIsTheIdentity() {
        Assertions.assertEquals(BTCB2_USD, ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, BTC_USD), 1e-9);
    }

    /**
     * A wallet is not worth nothing merely because a market has never traded.
     *
     * <p>Neoxa reports a last price of zero for a listed but untraded pair, and BTCB2 has three of
     * those. Rendering that as a price says the coins are worthless rather than that the price is
     * unknown.
     */
    @Test
    public void anUntradedPairHasNoPriceRatherThanAPriceOfZero() {
        Assertions.assertNull(ExchangeSource.validPrice(0.0d));
        Assertions.assertNull(ExchangeSource.validPrice(-1.0d));
        Assertions.assertNull(ExchangeSource.validPrice(null));
        Assertions.assertNull(ExchangeSource.validPrice(Double.NaN));
        Assertions.assertNull(ExchangeSource.validPrice(Double.POSITIVE_INFINITY));

        Assertions.assertEquals(BTCB2_USD, ExchangeSource.validPrice(BTCB2_USD), 1e-9);
    }

    /**
     * Half a conversion is not a conversion. If either leg is missing the answer is no rate, never
     * the dollar figure relabelled with another currency's symbol.
     */
    @Test
    public void aMissingLegProducesNoRateRatherThanADollarFigureInDisguise() {
        Assertions.assertNull(ExchangeSource.usdToCurrency(null, BTC_USD, BTC_EUR));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, null, BTC_EUR));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, null));
    }

    @Test
    public void anUnusableBtcQuoteProducesNoRate() {
        //Would divide by zero.
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, 0.0d, BTC_EUR));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, -BTC_USD, BTC_EUR));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, 0.0d));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, Double.NaN, BTC_EUR));
        Assertions.assertNull(ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, Double.POSITIVE_INFINITY));
    }

    /**
     * Where the whole change came from. Sparrow used to show this wallet's holdings at the SHA256
     * chain's price, which on the day was 114 times the value of what the wallet actually holds.
     */
    @Test
    public void theEstimateIsNotTheSha256ChainsPrice() {
        double shown = ExchangeSource.usdToCurrency(BTCB2_USD, BTC_USD, BTC_USD);
        Assertions.assertTrue(shown < BTC_USD / 100d,
                "a BTCB2 balance must not be valued anywhere near a BTC balance");
    }

    @Test
    public void thereIsNoHistoryToChartYet() {
        Assertions.assertEquals(Collections.emptyMap(),
                ExchangeSource.NEOXA.getHistoricalExchangeRates(Currency.getInstance("USD"), new Date(0), new Date()));
    }
}
