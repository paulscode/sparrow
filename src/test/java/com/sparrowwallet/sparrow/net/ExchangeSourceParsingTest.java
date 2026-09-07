package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Currency;
import java.util.List;

/**
 * The fiat estimate against the bytes the two services actually send.
 *
 * <p>Everything else about this source is arithmetic, and arithmetic was already covered. What was not
 * is the step in between: that a real response deserializes into the shapes it is read through. Getting
 * that wrong does not fail loudly. The request succeeds, parsing throws, the throw is caught and logged,
 * and the wallet quietly shows no fiat at all, which is indistinguishable from the exchange being down.
 *
 * <p>The fixtures are unedited captures from the live endpoints, so they carry every field those
 * services send rather than the ones considered while writing the DTOs.
 */
public class ExchangeSourceParsingTest {
    /**
     * Deliberately stricter than the client used in production.
     *
     * <p>Unknown properties fail here. Both feeds carry fields this wallet does not read, and both add
     * more over time, so this asserts the annotations that tolerate them are actually present and
     * correct. Passing under the strictest setting means passing under whatever the HTTP client
     * configures.
     */
    private static <T> T parse(String fixture, Class<T> type) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try(InputStream in = ExchangeSourceParsingTest.class.getResourceAsStream(fixture)) {
            Assertions.assertNotNull(in, "missing fixture " + fixture);
            return mapper.readValue(in, type);
        }
    }

    @Test
    public void theLiveTickerFeedYieldsAPrice() throws Exception {
        ExchangeSource.NeoxaTickers tickers = parse("neoxa-cmc-ticker.json", ExchangeSource.NeoxaTickers.class);

        Assertions.assertFalse(tickers.tickers.isEmpty(), "the object is keyed by pair, and every key is data");
        Assertions.assertTrue(tickers.tickers.containsKey("BTCB2_USDC"), "the pair the dollar price is read from");

        Double price = ExchangeSource.btcb2Price(tickers);
        Assertions.assertNotNull(price, "a real capture must yield a price");
        Assertions.assertTrue(price > 0.0d);
    }

    /**
     * BTCB2 has pairs that have never traded, and those report a last price of zero. Rendering one as a
     * price would say the coins are worth nothing rather than that the price is unknown, so the fixture
     * is checked to still contain such a pair: it is what makes the guard worth having.
     */
    @Test
    public void theLiveFeedStillCarriesUntradedPairs() throws Exception {
        ExchangeSource.NeoxaTickers tickers = parse("neoxa-cmc-ticker.json", ExchangeSource.NeoxaTickers.class);

        long untraded = tickers.tickers.values().stream()
                .filter(t -> t.last_price != null && t.last_price == 0.0d).count();
        Assertions.assertTrue(untraded > 0,
                "the guard against reporting zero as a price is exercised by real data, not only by a unit test");
    }

    @Test
    public void aFeedWithoutThePairYieldsNoPrice() {
        Assertions.assertNull(ExchangeSource.btcb2Price(new ExchangeSource.NeoxaTickers()));
        Assertions.assertNull(ExchangeSource.btcb2Price(null));
    }

    @Test
    public void theLiveRateTableYieldsTheDollarQuoteAndTheFiatList() throws Exception {
        ExchangeSource.CoinGeckoRates rates = parse("coingecko-exchange-rates.json", ExchangeSource.CoinGeckoRates.class);

        Double usd = ExchangeSource.getRate(rates, "USD");
        Assertions.assertNotNull(usd, "the divisor the conversion depends on");
        Assertions.assertTrue(usd > 0.0d);

        //Case matters: the table is keyed in lower case and currency codes are upper
        Assertions.assertEquals(usd, ExchangeSource.getRate(rates, "usd"));

        List<Currency> fiat = ExchangeSource.fiatCurrencies(rates);
        Assertions.assertTrue(fiat.size() > 20, "a real capture lists many fiat currencies, got " + fiat.size());
        Assertions.assertTrue(fiat.contains(Currency.getInstance("EUR")));
        Assertions.assertFalse(fiat.contains(Currency.getInstance("USD")) && fiat.indexOf(Currency.getInstance("USD")) < 0);
    }

    /**
     * The crypto entries in that table are not currencies, and one of them is bitcoin quoted at 1. Left
     * in, the picker would offer them and the conversion would divide by a rate that is not a currency.
     */
    @Test
    public void theRateTableCryptoEntriesAreNotOffered() throws Exception {
        ExchangeSource.CoinGeckoRates rates = parse("coingecko-exchange-rates.json", ExchangeSource.CoinGeckoRates.class);
        List<Currency> fiat = ExchangeSource.fiatCurrencies(rates);

        for(Currency currency : fiat) {
            Assertions.assertEquals(3, currency.getCurrencyCode().length(),
                    currency + " is not an ISO 4217 code");
        }
        Assertions.assertTrue(rates.rates.containsKey("btc"), "precondition: the table does carry crypto");
    }

    /**
     * End to end over both captures, through the same call the wallet makes.
     */
    @Test
    public void theTwoLiveResponsesProduceAPlausibleEstimate() throws Exception {
        Double btcb2Usd = ExchangeSource.btcb2Price(parse("neoxa-cmc-ticker.json", ExchangeSource.NeoxaTickers.class));
        ExchangeSource.CoinGeckoRates rates = parse("coingecko-exchange-rates.json", ExchangeSource.CoinGeckoRates.class);

        Double eur = ExchangeSource.usdToCurrency(btcb2Usd, ExchangeSource.getRate(rates, "USD"), ExchangeSource.getRate(rates, "EUR"));
        Assertions.assertNotNull(eur);

        //The point of the whole change: this is BTCB2's own price, nowhere near bitcoin's.
        Double btcUsd = ExchangeSource.getRate(rates, "USD");
        Assertions.assertTrue(btcb2Usd < btcUsd / 50d,
                "a BTCB2 estimate of " + btcb2Usd + " must not be anywhere near the BTC price of " + btcUsd);
        Assertions.assertTrue(eur > 0d && eur < btcb2Usd * 2d, "a euro figure in the same order as the dollar one");
    }
}
