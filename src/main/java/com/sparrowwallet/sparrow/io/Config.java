package com.sparrowwallet.sparrow.io;

import com.google.gson.*;
import com.sparrowwallet.drongo.BitcoinUnit;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.sparrow.UnitFormat;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.Theme;
import com.sparrowwallet.sparrow.control.QRDensity;
import com.sparrowwallet.sparrow.control.QREncoding;
import com.sparrowwallet.sparrow.control.WebcamResolution;
import com.sparrowwallet.sparrow.net.*;
import com.sparrowwallet.sparrow.wallet.FeeRatesSelection;
import com.sparrowwallet.sparrow.wallet.OptimizationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

import static com.sparrowwallet.sparrow.AppServices.ENUMERATE_HW_PERIOD_SECS;
import static com.sparrowwallet.sparrow.net.PagedBatchRequestBuilder.DEFAULT_PAGE_SIZE;
import static com.sparrowwallet.sparrow.net.TcpTransport.DEFAULT_MAX_TIMEOUT;
import static com.sparrowwallet.sparrow.wallet.WalletUtxosEntry.DUST_ATTACK_THRESHOLD_SATS;
import static com.sparrowwallet.sparrow.wallet.WalletUtxosEntry.DUST_ATTACK_THRESHOLD_SP_SATS;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public static final String CONFIG_FILENAME = "config";

    private Mode mode;
    private BitcoinUnit bitcoinUnit;
    private UnitFormat unitFormat;
    private Server blockExplorer;
    private FeeRatesSource feeRatesSource;
    private FeeRatesSelection feeRatesSelection;
    private OptimizationStrategy sendOptimizationStrategy;
    private Currency fiatCurrency;
    private ExchangeSource exchangeSource;
    private boolean loadRecentWallets = true;
    private boolean validateDerivationPaths = true;
    private boolean groupByAddress = true;
    private boolean includeMempoolOutputs = true;
    private boolean notifyNewTransactions = true;
    private boolean checkNewVersions = true;
    private Theme theme;
    private boolean openWalletsInNewWindows = false;
    private boolean chunkAddresses = true;
    private boolean hideEmptyUsedAddresses = false;
    private boolean hideAmounts = false;
    private boolean showTransactionHex = true;
    private boolean showLoadingLog = true;
    private boolean showAddressTransactionCount = false;
    private boolean showDeprecatedImportExport = false;
    private boolean signBsmsExports = false;
    private boolean preventSleep = false;
    private boolean verifyTransactions = true;
    private Boolean connectToBroadcast;
    private Boolean connectToResolve;
    private Boolean suggestSendToMany;
    private Boolean suggestChangeWalletsDir;
    private File walletsDir;
    private List<File> recentWalletFiles;
    private Integer keyDerivationPeriod;
    private long dustAttackThreshold = DUST_ATTACK_THRESHOLD_SATS;
    private long dustAttackThresholdSp = DUST_ATTACK_THRESHOLD_SP_SATS;
    private int enumerateHwPeriod = ENUMERATE_HW_PERIOD_SECS;
    private QRDensity qrDensity;
    private QREncoding qrEncoding;
    private WebcamResolution webcamResolution;
    private boolean mirrorCapture = true;
    private boolean useZbar = true;
    private String webcamDevice;
    private String webcamDeviceId;
    private ServerType serverType;
    private Server publicElectrumServer;
    private Server coreServer;
    private List<Server> recentCoreServers;
    private CoreAuthType coreAuthType;
    private File coreDataDir;
    private String coreAuth;
    private boolean useLegacyCoreWallet;
    private boolean legacyServer;
    private Server electrumServer;
    private List<Server> recentElectrumServers;
    private File electrumServerCert;
    private boolean useProxy;
    private String proxyServer;
    private boolean autoSwitchProxy = true;
    private int maxServerTimeout = DEFAULT_MAX_TIMEOUT;
    private int maxPageSize = DEFAULT_PAGE_SIZE;
    private boolean usePayNym;
    private boolean mempoolFullRbf;
    private double minRelayFeeRate = Transaction.DEFAULT_MIN_RELAY_FEE;
    private Double appWidth;
    private Double appHeight;

    private static Config INSTANCE;

    private static Gson getGson() {
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(File.class, new FileSerializer());
        gsonBuilder.registerTypeAdapter(File.class, new FileDeserializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerSerializer());
        gsonBuilder.registerTypeAdapter(Server.class, new ServerDeserializer());
        return gsonBuilder.setPrettyPrinting().disableHtmlEscaping().create();
    }

    private static File getConfigFile() {
        File configDir = Storage.getConfigDir();
        return new File(configDir, CONFIG_FILENAME);
    }

    private static Config load() {
        File configFile = getConfigFile();
        if(configFile.exists()) {
            try {
                Reader reader = new FileReader(configFile);
                Config config = getGson().fromJson(reader, Config.class);
                reader.close();

                if(config != null) {
                    return config;
                }
            } catch(Exception e) {
                log.error("Error opening " + configFile.getAbsolutePath(), e);
                //Ignore and assume no config
            }
        }

        return new Config();
    }

    public static synchronized Config get() {
        if(INSTANCE == null) {
            INSTANCE = load();
        }

        return INSTANCE;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        flush();
    }

    public BitcoinUnit getBitcoinUnit() {
        return bitcoinUnit;
    }

    public void setBitcoinUnit(BitcoinUnit bitcoinUnit) {
        this.bitcoinUnit = bitcoinUnit;
        flush();
    }

    public UnitFormat getUnitFormat() {
        return unitFormat;
    }

    public void setUnitFormat(UnitFormat unitFormat) {
        this.unitFormat = unitFormat;
        flush();
    }

    /**
     * Whether opening a txid should do nothing.
     *
     * <p>Reads through {@link #getBlockExplorer()} rather than the stored field, because there are now
     * three ways to end up with no explorer and only one of them is the None entry: nothing was ever
     * chosen, or what was chosen follows the chain that kept SHA256d and is refused. Comparing the
     * field caught only the third, and the caller then fell back to None's placeholder URL and opened
     * "http://none/tx/..." in the browser.
     */
    public boolean isBlockExplorerDisabled() {
        return BlockExplorer.NONE.getServer().equals(getEffectiveBlockExplorer());
    }

    /**
     * The explorer a txid would actually be opened in: what is configured, or the default.
     *
     * <p>Named once so that whether the link is offered and where it goes cannot disagree. They did
     * briefly: the default became an explorer that works while the decision to offer the link still
     * read an unset value as nothing to open, which would have hidden the entry while the default sat
     * there perfectly usable.
     */
    public Server getEffectiveBlockExplorer() {
        Server explorer = getBlockExplorer();
        return explorer == null ? BlockExplorer.MEMPOOL_GUIDE.getServer() : explorer;
    }

    /**
     * The configured block explorer, unless it follows the chain that kept SHA256d.
     *
     * <p>Withdrawing those explorers from the picker does nothing for an install that already chose one:
     * the value is a URL rather than an enum, so it survives, is added back to the list because it is
     * not among the offered ones, and keeps being opened. A txid from this chain is either absent there
     * or present because the transaction was replayed, and the second reads as a confirmation on this
     * chain.
     *
     * <p>Filtered on read rather than migrated on load, so it also covers a config edited by hand or
     * carried over from an install of upstream Sparrow.
     */
    public Server getBlockExplorer() {
        if(blockExplorer != null && followsOtherChain(blockExplorer.getUrl())) {
            return null;
        }

        return blockExplorer;
    }

    /**
     * Hosts withdrawn because they index the chain that kept SHA256d. Matched on host rather than on the
     * whole URL, since a stored value may carry a {0} txid placeholder or a path.
     */
    private static boolean followsOtherChain(String url) {
        if(url == null) {
            return false;
        }

        String lower = url.toLowerCase(java.util.Locale.ROOT);
        //The onion addresses too. They are the same services, they are what a Tor user would have been
        //given, and matching only the clearnet names would let exactly the privacy-minded configuration
        //through. Taken from the broadcast sources, which carried both forms of each.
        for(String host : List.of(
                "mempool.space",
                "blockstream.info",
                "mempool.emzy.de",
                "mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion",
                "explorerzydxu5ecjrkwceayqybizmpjjznk5izmitf2modhcusuqlid.onion",
                "mempool4t6mypeemozyterviq3i5de4kpoua65r3qkn5i3kknu5l2cad.onion")) {
            if(lower.contains(host)) {
                return true;
            }
        }

        return false;
    }

    public void setBlockExplorer(Server blockExplorer) {
        this.blockExplorer = blockExplorer;
        flush();
    }

    /**
     * The configured fee rate source, unless it is one that was withdrawn.
     *
     * <p>An install that chose mempool.space keeps that in its config, and the picker would show it as
     * selected while nothing would use it, because the source no longer supports this network. Reading
     * it as absent puts such an install on the connected server, which is what it would get anyway, and
     * makes the screen agree with what is happening.
     */
    public FeeRatesSource getFeeRatesSource() {
        if(feeRatesSource != null && !feeRatesSource.supportsNetwork(Network.get())) {
            return null;
        }

        return feeRatesSource;
    }

    public void setFeeRatesSource(FeeRatesSource feeRatesSource) {
        this.feeRatesSource = feeRatesSource;
        flush();
    }

    public FeeRatesSelection getFeeRatesSelection() {
        return feeRatesSelection;
    }

    public void setFeeRatesSelection(FeeRatesSelection feeRatesSelection) {
        this.feeRatesSelection = feeRatesSelection;
        flush();
    }

    public OptimizationStrategy getSendOptimizationStrategy() {
        return sendOptimizationStrategy;
    }

    public void setSendOptimizationStrategy(OptimizationStrategy sendOptimizationStrategy) {
        this.sendOptimizationStrategy = sendOptimizationStrategy;
        flush();
    }

    public Currency getFiatCurrency() {
        return fiatCurrency;
    }

    public void setFiatCurrency(Currency fiatCurrency) {
        this.fiatCurrency = fiatCurrency;
        flush();
    }

    public boolean isFetchRates() {
        return getExchangeSource() != ExchangeSource.NONE;
    }

    public ExchangeSource getExchangeSource() {
        return exchangeSource;
    }

    public void setExchangeSource(ExchangeSource exchangeSource) {
        this.exchangeSource = exchangeSource;
        flush();
    }

    public boolean isLoadRecentWallets() {
        return loadRecentWallets;
    }

    public void setLoadRecentWallets(boolean loadRecentWallets) {
        this.loadRecentWallets = loadRecentWallets;
        flush();
    }

    public boolean isValidateDerivationPaths() {
        return validateDerivationPaths;
    }

    public void setValidateDerivationPaths(boolean validateDerivationPaths) {
        this.validateDerivationPaths = validateDerivationPaths;
        flush();
    }

    public boolean isGroupByAddress() {
        return groupByAddress;
    }

    public void setGroupByAddress(boolean groupByAddress) {
        this.groupByAddress = groupByAddress;
        flush();
    }

    public boolean isIncludeMempoolOutputs() {
        return includeMempoolOutputs;
    }

    public void setIncludeMempoolOutputs(boolean includeMempoolOutputs) {
        this.includeMempoolOutputs = includeMempoolOutputs;
        flush();
    }

    public boolean isNotifyNewTransactions() {
        return notifyNewTransactions;
    }

    public void setNotifyNewTransactions(boolean notifyNewTransactions) {
        this.notifyNewTransactions = notifyNewTransactions;
        flush();
    }

    public boolean isCheckNewVersions() {
        return checkNewVersions;
    }

    public void setCheckNewVersions(boolean checkNewVersions) {
        this.checkNewVersions = checkNewVersions;
        flush();
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        flush();
    }

    public boolean isOpenWalletsInNewWindows() {
        return openWalletsInNewWindows;
    }

    public void setOpenWalletsInNewWindows(boolean openWalletsInNewWindows) {
        this.openWalletsInNewWindows = openWalletsInNewWindows;
        flush();
    }

    public boolean isChunkAddresses() {
        return chunkAddresses;
    }

    public void setChunkAddresses(boolean chunkAddresses) {
        this.chunkAddresses = chunkAddresses;
        flush();
    }

    public boolean isHideEmptyUsedAddresses() {
        return hideEmptyUsedAddresses;
    }

    public void setHideEmptyUsedAddresses(boolean hideEmptyUsedAddresses) {
        this.hideEmptyUsedAddresses = hideEmptyUsedAddresses;
        flush();
    }

    public boolean isHideAmounts() {
        return hideAmounts;
    }

    public void setHideAmounts(boolean hideAmounts) {
        this.hideAmounts = hideAmounts;
        flush();
    }

    public boolean isShowTransactionHex() {
        return showTransactionHex;
    }

    public void setShowTransactionHex(boolean showTransactionHex) {
        this.showTransactionHex = showTransactionHex;
        flush();
    }

    public boolean isShowLoadingLog() {
        return showLoadingLog;
    }

    public void setShowLoadingLog(boolean showLoadingLog) {
        this.showLoadingLog = showLoadingLog;
        flush();
    }

    public boolean isShowAddressTransactionCount() {
        return showAddressTransactionCount;
    }

    public void setShowAddressTransactionCount(boolean showAddressTransactionCount) {
        this.showAddressTransactionCount = showAddressTransactionCount;
        flush();
    }

    public boolean isShowDeprecatedImportExport() {
        return showDeprecatedImportExport;
    }

    public void setShowDeprecatedImportExport(boolean showDeprecatedImportExport) {
        this.showDeprecatedImportExport = showDeprecatedImportExport;
        flush();
    }

    public boolean isSignBsmsExports() {
        return signBsmsExports;
    }

    public void setSignBsmsExports(boolean signBsmsExports) {
        this.signBsmsExports = signBsmsExports;
        flush();
    }

    public boolean isPreventSleep() {
        return preventSleep;
    }

    public void setPreventSleep(boolean preventSleep) {
        this.preventSleep = preventSleep;
        flush();
    }

    public boolean isVerifyTransactions() {
        return verifyTransactions;
    }

    public void setVerifyTransactions(boolean verifyTransactions) {
        this.verifyTransactions = verifyTransactions;
        flush();
    }

    public Boolean getConnectToBroadcast() {
        return connectToBroadcast;
    }

    public void setConnectToBroadcast(Boolean connectToBroadcast) {
        this.connectToBroadcast = connectToBroadcast;
        flush();
    }

    public Boolean getConnectToResolve() {
        return connectToResolve;
    }

    public void setConnectToResolve(Boolean connectToResolve) {
        this.connectToResolve = connectToResolve;
        flush();
    }

    public Boolean getSuggestSendToMany() {
        return suggestSendToMany;
    }

    public void setSuggestSendToMany(Boolean suggestSendToMany) {
        this.suggestSendToMany = suggestSendToMany;
        flush();
    }

    public Boolean getSuggestChangeWalletsDir() {
        return suggestChangeWalletsDir;
    }

    public void setSuggestChangeWalletsDir(Boolean suggestChangeWalletsDir) {
        this.suggestChangeWalletsDir = suggestChangeWalletsDir;
        flush();
    }

    public File getWalletsDir() {
        return walletsDir;
    }

    public void setWalletsDir(File walletsDir) {
        this.walletsDir = walletsDir;
        flush();
    }

    public List<File> getRecentWalletFiles() {
        return recentWalletFiles;
    }

    public void setRecentWalletFiles(List<File> recentWalletFiles) {
        this.recentWalletFiles = recentWalletFiles;
        flush();
    }

    public Integer getKeyDerivationPeriod() {
        return keyDerivationPeriod;
    }

    public void setKeyDerivationPeriod(Integer keyDerivationPeriod) {
        this.keyDerivationPeriod = keyDerivationPeriod;
        flush();
    }

    public long getDustAttackThreshold() {
        return dustAttackThreshold;
    }

    public long getDustAttackThresholdSp() {
        return dustAttackThresholdSp;
    }

    public int getEnumerateHwPeriod() {
        return enumerateHwPeriod;
    }

    public QRDensity getQrDensity() {
        return qrDensity == null ? QRDensity.NORMAL : qrDensity;
    }

    public void setQrDensity(QRDensity qrDensity) {
        this.qrDensity = qrDensity;
        flush();
    }

    public QREncoding getQrEncoding() {
        return qrEncoding;
    }

    public void setQrEncoding(QREncoding qrEncoding) {
        this.qrEncoding = qrEncoding;
        flush();
    }

    public WebcamResolution getWebcamResolution() {
        return webcamResolution;
    }

    public void setWebcamResolution(WebcamResolution webcamResolution) {
        this.webcamResolution = webcamResolution;
        flush();
    }

    public boolean isMirrorCapture() {
        return mirrorCapture;
    }

    public void setMirrorCapture(boolean mirrorCapture) {
        this.mirrorCapture = mirrorCapture;
        flush();
    }

    public boolean isUseZbar() {
        return useZbar;
    }

    public String getWebcamDevice() {
        return webcamDevice;
    }

    public void setWebcamDevice(String webcamDevice) {
        this.webcamDevice = webcamDevice;
        flush();
    }

    public String getWebcamDeviceId() {
        return webcamDeviceId;
    }

    public void setWebcamDeviceId(String webcamDeviceId) {
        this.webcamDeviceId = webcamDeviceId;
        flush();
    }

    public ServerType getServerType() {
        return serverType;
    }

    public void setServerType(ServerType serverType) {
        this.serverType = serverType;
        flush();
    }

    public boolean hasServer() {
        return getServer() != null;
    }

    public Server getServer() {
        return getServerType() == ServerType.BITCOIN_CORE ? getCoreServer() : (getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER ? getPublicElectrumServer() : getElectrumServer());
    }

    public String getServerDisplayName() {
        return getServer() == null ? "server" : getServer().getDisplayName();
    }

    public boolean requiresInternalTor() {
        if(isUseProxy()) {
            return false;
        }

        return requiresTor();
    }

    public boolean requiresTor() {
        if(!hasServer()) {
            return false;
        }

        return getServer().isOnionAddress();
    }

    /**
     * The configured public Electrum server, of which there are none on this chain.
     *
     * <p>An install that was connected to one keeps it in its config, and {@link #getServer()} hands it
     * straight back, so it would go on connecting to a server that indexes the chain that kept SHA256d.
     * That does not fail visibly: it syncs, and then shows that chain's blocks, history and balances
     * against addresses this wallet derives. The settings screen substitutes Bitcoin Core for the server
     * type, but only once that screen is opened, which is after the connection has been made.
     *
     * <p>Refused here rather than by rewriting the server type, because the type is read by things that
     * have nothing to do with connecting, such as whether transaction proofs are verified and how long
     * to wait before retrying. Nothing to connect to is the whole of what is wanted, and it leaves an
     * install on nothing rather than on the wrong chain.
     */
    public Server getPublicElectrumServer() {
        if(!PublicElectrumServer.supportedNetwork()) {
            return null;
        }

        return publicElectrumServer;
    }

    public void setPublicElectrumServer(Server publicElectrumServer) {
        this.publicElectrumServer = publicElectrumServer;
        flush();
    }

    public Server getCoreServer() {
        return coreServer;
    }

    public void setCoreServer(Server coreServer) {
        this.coreServer = coreServer;
        flush();
    }

    public List<Server> getRecentCoreServers() {
        return recentCoreServers == null ? new ArrayList<>() : recentCoreServers;
    }

    public boolean addRecentCoreServer(Server coreServer) {
        if(recentCoreServers == null) {
            recentCoreServers = new ArrayList<>();
        }

        int index = getRecentCoreServers().indexOf(coreServer);
        if(index < 0) {
            recentCoreServers.removeIf(server -> server.getHost().equals(coreServer.getHost()) && server.getAlias() == null);
            recentCoreServers.add(coreServer);
            flush();
            return true;
        }

        return false;
    }

    public void removeRecentCoreServer(Server server) {
        int index = getRecentCoreServers().indexOf(server);
        if(index >= 0) {
            recentCoreServers.remove(index);
            flush();
        }
    }

    public void setCoreServerAlias(Server server) {
        int index = getRecentCoreServers().indexOf(server);
        if(index >= 0) {
            recentCoreServers.set(index, server);
            flush();
        }
    }

    public CoreAuthType getCoreAuthType() {
        return coreAuthType;
    }

    public void setCoreAuthType(CoreAuthType coreAuthType) {
        this.coreAuthType = coreAuthType;
        flush();
    }

    public File getCoreDataDir() {
        return coreDataDir;
    }

    public void setCoreDataDir(File coreDataDir) {
        this.coreDataDir = coreDataDir;
        flush();
    }

    public String getCoreAuth() {
        return coreAuth;
    }

    public void setCoreAuth(String coreAuth) {
        this.coreAuth = coreAuth;
        flush();
    }

    public boolean isUseLegacyCoreWallet() {
        return useLegacyCoreWallet;
    }

    public void setUseLegacyCoreWallet(boolean useLegacyCoreWallet) {
        this.useLegacyCoreWallet = useLegacyCoreWallet;
        flush();
    }

    public boolean isLegacyServer() {
        return legacyServer;
    }

    public void setLegacyServer(boolean legacyServer) {
        this.legacyServer = legacyServer;
        flush();
    }

    public Server getElectrumServer() {
        return electrumServer;
    }

    public void setElectrumServer(Server electrumServer) {
        this.electrumServer = electrumServer;
        flush();
    }

    public List<Server> getRecentElectrumServers() {
        return recentElectrumServers == null ? new ArrayList<>() : recentElectrumServers;
    }

    public boolean addRecentServer() {
        if(serverType == ServerType.BITCOIN_CORE && coreServer != null) {
            return addRecentCoreServer(coreServer);
        } else if(serverType == ServerType.ELECTRUM_SERVER && electrumServer != null) {
            return addRecentElectrumServer(electrumServer);
        }

        return false;
    }

    public boolean addRecentElectrumServer(Server electrumServer) {
        if(recentElectrumServers == null) {
            recentElectrumServers = new ArrayList<>();
        }

        int index = getRecentElectrumServers().indexOf(electrumServer);
        if(index < 0) {
            recentElectrumServers.removeIf(server -> server.getHost().equals(electrumServer.getHost()) && server.getAlias() == null);
            recentElectrumServers.add(electrumServer);
            flush();

            return true;
        }

        return false;
    }

    public void removeRecentElectrumServer(Server server) {
        int index = getRecentElectrumServers().indexOf(server);
        if(index >= 0) {
            recentElectrumServers.remove(index);
            flush();
        }
    }

    public void setElectrumServerAlias(Server server) {
        int index = getRecentElectrumServers().indexOf(server);
        if(index >= 0) {
            recentElectrumServers.set(index, server);
            flush();
        }
    }

    public File getElectrumServerCert() {
        return electrumServerCert;
    }

    public void setElectrumServerCert(File electrumServerCert) {
        this.electrumServerCert = electrumServerCert;
        flush();
    }

    public boolean isUseProxy() {
        return useProxy;
    }

    public void setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        flush();
    }

    public String getProxyServer() {
        return proxyServer;
    }

    public void setProxyServer(String proxyServer) {
        this.proxyServer = proxyServer;
        flush();
    }

    public boolean isAutoSwitchProxy() {
        return autoSwitchProxy;
    }

    public void setAutoSwitchProxy(boolean autoSwitchProxy) {
        this.autoSwitchProxy = autoSwitchProxy;
        flush();
    }

    public int getMaxServerTimeout() {
        return maxServerTimeout;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public boolean isUsePayNym() {
        return usePayNym;
    }

    public void setUsePayNym(boolean usePayNym) {
        this.usePayNym = usePayNym;
        flush();
    }

    public boolean isMempoolFullRbf() {
        return mempoolFullRbf;
    }

    public void setMempoolFullRbf(boolean mempoolFullRbf) {
        this.mempoolFullRbf = mempoolFullRbf;
        flush();
    }

    public double getMinRelayFeeRate() {
        return minRelayFeeRate;
    }

    public void setMinRelayFeeRate(double minRelayFeeRate) {
        this.minRelayFeeRate = minRelayFeeRate;
    }

    public Double getAppWidth() {
        return appWidth;
    }

    public void setAppWidth(Double appWidth) {
        this.appWidth = appWidth;
        flush();
    }

    public Double getAppHeight() {
        return appHeight;
    }

    public void setAppHeight(Double appHeight) {
        this.appHeight = appHeight;
        flush();
    }

    private synchronized void flush() {
        Gson gson = getGson();
        try {
            File configFile = getConfigFile();
            if(!configFile.exists()) {
                Storage.createOwnerOnlyFile(configFile);
            }

            Writer writer = new FileWriter(configFile);
            gson.toJson(this, writer);
            writer.flush();
            writer.close();
        } catch (IOException e) {
            //Ignore
        }
    }

    private static class FileSerializer implements JsonSerializer<File> {
        @Override
        public JsonElement serialize(File src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.getAbsolutePath());
        }
    }

    private static class FileDeserializer implements JsonDeserializer<File> {
        @Override
        public File deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return new File(json.getAsJsonPrimitive().getAsString());
        }
    }

    private static class ServerSerializer implements JsonSerializer<Server> {
        @Override
        public JsonElement serialize(Server src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }
    }

    private static class ServerDeserializer implements JsonDeserializer<Server> {
        @Override
        public Server deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return Server.fromString(json.getAsJsonPrimitive().getAsString());
        }
    }
}
