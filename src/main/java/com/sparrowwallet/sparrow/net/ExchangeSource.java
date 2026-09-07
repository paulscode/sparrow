package com.sparrowwallet.sparrow.net;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.event.ExchangeRatesUpdatedEvent;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Where the fiat estimate beside a coin amount comes from.
 *
 * <p>Upstream Sparrow offers Coinbase, Coingecko and mempool.space, all of which price BTC on the
 * SHA256 chain. This wallet holds BTCB2, so every one of those quotes the wrong asset: with BTC near
 * $79,800 and BTCB2 near $700, they overstate a balance by roughly 114 times. A wrong number carries
 * further than a missing one, so they are gone rather than merely not the default. What is left is
 * the chain's own market, and None.
 */
public enum ExchangeSource {
    NONE("None", null) {
        @Override
        public List<Currency> getSupportedCurrencies() {
            return Collections.emptyList();
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            return null;
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            return Collections.emptyMap();
        }
    },
    NEOXA("Neoxa", "No historical rates") {
        @Override
        public List<Currency> getSupportedCurrencies() {
            //Every fiat Coingecko lists, because the dollar price is carried into the others by the
            //conversion in usdToCurrency. Coingecko is not selectable as a source any more; it is
            //used here only to turn dollars into another currency.
            List<Currency> converted = getCoinGeckoRates().rates.entrySet().stream()
                    .filter(rate -> "fiat".equals(rate.getValue().type) && isValidISO4217Code(rate.getKey().toUpperCase(Locale.ROOT)))
                    .map(rate -> Currency.getInstance(rate.getKey().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toList());

            return withUsd(converted);
        }

        @Override
        public Double getExchangeRate(Currency currency) {
            Double btcb2Usd = getBtcb2Usd();
            if(btcb2Usd == null) {
                return null;
            }

            if(USD.equalsIgnoreCase(currency.getCurrencyCode())) {
                return btcb2Usd;
            }

            CoinGeckoRates rates = getCoinGeckoRates();
            return usdToCurrency(btcb2Usd, getRate(rates, USD), getRate(rates, currency.getCurrencyCode()));
        }

        /**
         * BTCB2 in dollars, from the deepest market it has.
         *
         * <p>BTCB2_USDC is used rather than BTCB2_BTC multiplied by an outside BTC price, which is
         * the obvious construction and is wrong here. The BTCB2/BTC pair is quoted against Neoxa's
         * own BTC market, and that market is thin enough to drift: it has priced BTC around $74,100
         * while the wider market was near $79,857, on a day's volume of 2.5 BTC. Multiplying by an
         * outside BTC price therefore counts that gap twice and reads about 9% high ($763 against
         * the $700 the USDC market and the USDT market both agree on).
         *
         * <p>USDC is treated as a dollar. That is an approximation, and this venue is loose enough
         * that its own USDT/USDC pair does not reconcile with its two BTCB2 pairs to better than a
         * few percent. It is well inside the error that matters for an estimate on an asset that
         * moved 47% in a day.
         */
        private Double getBtcb2Usd() {
            String url = "https://neoxa.exchange/api/v1/cmc/ticker";

            if(log.isInfoEnabled()) {
                log.info("Requesting exchange rates from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                NeoxaTickers tickers = httpClientService.requestJson(url, NeoxaTickers.class, HTTP_HEADERS);
                NeoxaTicker ticker = tickers.tickers.get(BTCB2_USD_PAIR);
                if(ticker == null) {
                    log.warn("No " + BTCB2_USD_PAIR + " market at " + url);
                    return null;
                }
                return validPrice(ticker.last_price);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency rates", e);
                } else {
                    log.warn("Error retrieving currency rates (" + e.getMessage() + ")");
                }
                return null;
            }
        }

        private Double getRate(CoinGeckoRates rates, String currencyCode) {
            return rates.rates.entrySet().stream()
                    .filter(rate -> currencyCode.equalsIgnoreCase(rate.getKey()))
                    .map(rate -> rate.getValue().value)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
        }

        private CoinGeckoRates getCoinGeckoRates() {
            String url = "https://api.coingecko.com/api/v3/exchange_rates";

            if(log.isInfoEnabled()) {
                log.info("Requesting currency conversions from " + url);
            }

            HttpClientService httpClientService = AppServices.getHttpClientService();
            try {
                return httpClientService.requestJson(url, CoinGeckoRates.class, HTTP_HEADERS);
            } catch(Exception e) {
                if(log.isDebugEnabled()) {
                    log.warn("Error retrieving currency conversions", e);
                } else {
                    log.warn("Error retrieving currency conversions (" + e.getMessage() + ")");
                }
                return new CoinGeckoRates();
            }
        }

        @Override
        public Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end) {
            //Neoxa serves individual trades rather than a daily series, and BTCB2 has only been
            //listed for days, so there is no history worth charting yet.
            return Collections.emptyMap();
        }
    };

    private static final Logger log = LoggerFactory.getLogger(ExchangeSource.class);
    private static final Map<String, String> HTTP_HEADERS = Map.of("User-Agent", "Mozilla/4.0 (compatible; MSIE 9.0; Windows NT 6.1)", "Accept", "*/*");

    private static final String USD = "USD";

    /**
     * The market the dollar price is read from. BTCB2's deepest pair by a wide margin: around 1126
     * BTCB2 a day against 19 on BTCB2_USDT, and quoted a tick wide at 699/700.
     */
    private static final String BTCB2_USD_PAIR = "BTCB2_USDC";

    private final String name;
    private final String description;

    ExchangeSource(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public abstract List<Currency> getSupportedCurrencies();

    public abstract Double getExchangeRate(Currency currency);

    public abstract Map<Date, Double> getHistoricalExchangeRates(Currency currency, Date start, Date end);

    /**
     * The convertible currencies, with dollars guaranteed to be among them and first.
     *
     * <p>Dollars are the currency BTCB2 actually trades in, and quoting it needs nothing but Neoxa.
     * Everything else is reached by borrowing a conversion from Coingecko, so a Coingecko outage
     * empties that list. Without this, such an outage would also take away the one currency that
     * was still working, and leave the picker disabled on a wallet whose rate was fine.
     *
     * <p>First, not merely present, because the picker falls back to the head of the list when it
     * has nothing configured to select.
     */
    static List<Currency> withUsd(List<Currency> converted) {
        List<Currency> currencies = new ArrayList<>();
        currencies.add(Currency.getInstance(USD));
        for(Currency currency : converted) {
            if(!currencies.contains(currency)) {
                currencies.add(currency);
            }
        }

        return currencies;
    }

    /**
     * A traded price, or null for one that is not.
     *
     * <p>A pair that has never traded reports zero, and a zero shown as a price does not read as
     * "unknown", it reads as "your coins are worth nothing". Absent is the honest rendering, and it
     * is what every other failure here produces.
     */
    static Double validPrice(Double lastPrice) {
        if(lastPrice == null || lastPrice <= 0.0d || !Double.isFinite(lastPrice)) {
            return null;
        }

        return lastPrice;
    }

    /**
     * A dollar price of BTCB2 restated in another currency.
     *
     * <p>The two arguments after the price are Coingecko's quotes for BTC in dollars and BTC in the
     * target currency. Dividing one by the other leaves the currency conversion and cancels BTC
     * out completely, so Coingecko's opinion of what bitcoin is worth cannot reach the result. That
     * is the point of doing it this way: only the ratio between two of its fiat quotes is used, and
     * a BTC price that is stale, wrong, or from a different chain entirely changes nothing.
     *
     * <p>Null if any input is missing or unusable, because a dollar figure wearing another
     * currency's symbol is worse than no figure.
     */
    static Double usdToCurrency(Double btcb2Usd, Double btcPerUsd, Double btcPerCurrency) {
        if(btcb2Usd == null || btcPerUsd == null || btcPerCurrency == null) {
            return null;
        }
        if(btcPerUsd <= 0.0d || btcPerCurrency <= 0.0d || !Double.isFinite(btcPerUsd) || !Double.isFinite(btcPerCurrency)) {
            return null;
        }

        return btcb2Usd * (btcPerCurrency / btcPerUsd);
    }

    private static boolean isValidISO4217Code(String code) {
        try {
            Currency currency = Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return name;
    }

    public SVGImage getSVGImage() {
        try {
            URL url = AppServices.class.getResource("/image/exchangesource/" + name.toLowerCase(Locale.ROOT) + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load exchange source image for " + name);
        }

        return null;
    }

    public static class CurrenciesService extends Service<List<Currency>> {
        private final ExchangeSource exchangeSource;

        public CurrenciesService(ExchangeSource exchangeSource) {
            this.exchangeSource = exchangeSource;
        }

        @Override
        protected Task<List<Currency>> createTask() {
            return new Task<>() {
                protected List<Currency> call() {
                    return exchangeSource.getSupportedCurrencies();
                }
            };
        }
    }

    public static class RatesService extends ScheduledService<ExchangeRatesUpdatedEvent> {
        private final ExchangeSource exchangeSource;
        private final Currency selectedCurrency;

        public RatesService(ExchangeSource exchangeSource, Currency selectedCurrency) {
            this.exchangeSource = exchangeSource;
            this.selectedCurrency = selectedCurrency;
        }

        protected Task<ExchangeRatesUpdatedEvent> createTask() {
            return new Task<>() {
                protected ExchangeRatesUpdatedEvent call() {
                    Double rate = exchangeSource.getExchangeRate(selectedCurrency);
                    return new ExchangeRatesUpdatedEvent(selectedCurrency, rate);
                }
            };
        }

        public ExchangeSource getExchangeSource() {
            return exchangeSource;
        }
    }

    private static class CoinGeckoRates {
        public Map<String, CoinGeckoRate> rates = new LinkedHashMap<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CoinGeckoRate {
        public String name;
        public String unit;
        public Double value;
        public String type;
    }

    /**
     * The CoinMarketCap-shaped feed, which is an object keyed by pair rather than a list. Captured
     * with an any-setter for the same reason mempool.space's rates were: the keys are the data.
     */
    private static class NeoxaTickers {
        public final Map<String, NeoxaTicker> tickers = new LinkedHashMap<>();

        @JsonAnyGetter
        public Map<String, NeoxaTicker> getTickers() {
            return tickers;
        }

        @JsonAnySetter
        public void setTicker(String name, NeoxaTicker value) {
            tickers.put(name, value);
        }
    }

    //Annotated because this feed carries fields we do not read (volumes, 24h ranges) and adds more
    //over time, and an unknown key must not be able to take the fiat estimate down with it.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class NeoxaTicker {
        public Double last_price;
        public Double highest_bid;
        public Double lowest_ask;
    }
}
