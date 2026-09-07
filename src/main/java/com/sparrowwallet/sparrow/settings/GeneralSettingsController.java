package com.sparrowwallet.sparrow.settings;

import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.control.TextfieldDialog;
import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.net.BlockExplorer;
import com.sparrowwallet.sparrow.net.ExchangeSource;
import com.sparrowwallet.sparrow.net.FeeRatesSource;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class GeneralSettingsController extends SettingsDetailController {
    private static final Logger log = LoggerFactory.getLogger(GeneralSettingsController.class);

    private static final Server CUSTOM_BLOCK_EXPLORER = new Server("http://custom.block.explorer");

    @FXML
    private ComboBox<FeeRatesSource> feeRatesSource;

    @FXML
    private ComboBox<Server> blockExplorers;

    @FXML
    private ComboBox<Currency> fiatCurrency;

    @FXML
    private ComboBox<ExchangeSource> exchangeSource;

    @FXML
    private Label currenciesLoadWarning;

    @FXML
    private UnlabeledToggleSwitch loadRecentWallets;

    @FXML
    private UnlabeledToggleSwitch validateDerivationPaths;

    @FXML
    private UnlabeledToggleSwitch groupByAddress;

    @FXML
    private UnlabeledToggleSwitch includeMempoolOutputs;

    @FXML
    private UnlabeledToggleSwitch notifyNewTransactions;

    @FXML
    private UnlabeledToggleSwitch checkNewVersions;

    private final ChangeListener<Currency> fiatCurrencyListener = new ChangeListener<Currency>() {
        @Override
        public void changed(ObservableValue<? extends Currency> observable, Currency oldValue, Currency newValue) {
            if (newValue != null) {
                Config.get().setFiatCurrency(newValue);
                EventManager.get().post(new FiatCurrencySelectedEvent(exchangeSource.getValue(), newValue));
            }
        }
    };

    @Override
    public void initializeView(Config config) {
        if(config.getFeeRatesSource() != null) {
            feeRatesSource.setValue(config.getFeeRatesSource());
        } else {
            //Named rather than selected by index. This was select(1), which meant mempool.space only
            //because of where it sat in the list; with the sources that follow the chain that kept
            //SHA256d withdrawn, index 1 is the fixed one-sat minimum, so a fresh install would have
            //quietly defaulted to underpaying every fee.
            feeRatesSource.setValue(FeeRatesSource.ELECTRUM_SERVER);
            config.setFeeRatesSource(feeRatesSource.getValue());
        }

        feeRatesSource.setCellFactory(_ -> new FeeRatesSourceListCell());
        feeRatesSource.setButtonCell(feeRatesSource.getCellFactory().call(null));
        feeRatesSource.valueProperty().addListener((observable, oldValue, newValue) -> {
            config.setFeeRatesSource(newValue);
            EventManager.get().post(new FeeRatesSourceChangedEvent(newValue));
        });

        currenciesLoadWarning.managedProperty().bind(currenciesLoadWarning.visibleProperty());
        currenciesLoadWarning.setVisible(false);

        blockExplorers.setItems(getBlockExplorerList());
        blockExplorers.setCellFactory(_ -> new BlockExplorerListCell());
        blockExplorers.setButtonCell(blockExplorers.getCellFactory().call(null));
        blockExplorers.valueProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null) {
                if(newValue == CUSTOM_BLOCK_EXPLORER) {
                    TextfieldDialog textfieldDialog = new TextfieldDialog();
                    textfieldDialog.initOwner(blockExplorers.getScene().getWindow());
                    textfieldDialog.setTitle("Enter Block Explorer URL");
                    textfieldDialog.setHeaderText("Enter the URL of the block explorer.\n\nIf present, the characters {0} will be replaced with the txid.\nFor example, https://localhost or https://localhost/tx/{0}\n");
                    textfieldDialog.getEditor().setPromptText("https://localhost");
                    Optional<String> optUrl = textfieldDialog.showAndWait();
                    if(optUrl.isPresent() && !optUrl.get().isEmpty()) {
                        try {
                            Server server = getBlockExplorer(optUrl.get());
                            config.setBlockExplorer(server);
                            EventManager.get().post(new BlockExplorerChangedEvent(config.getBlockExplorer()));
                            Platform.runLater(() -> {
                                blockExplorers.getSelectionModel().select(-1);
                                blockExplorers.setItems(getBlockExplorerList());
                                blockExplorers.setValue(Config.get().getBlockExplorer());
                            });
                        } catch(Exception e) {
                            AppServices.showErrorDialog("Invalid URL", "The URL " + optUrl.get() + " is not valid.");
                            blockExplorers.setValue(oldValue);
                        }
                    } else {
                        blockExplorers.setValue(oldValue);
                    }
                } else {
                    Config.get().setBlockExplorer(newValue);
                    //Told rather than left to be noticed. A transaction row decides whether to offer the
                    //explorer when its cell is drawn, and switching tabs does not redraw a valid cell, so
                    //without this the setting appeared to do nothing until the wallet was restarted.
                    EventManager.get().post(new BlockExplorerChangedEvent(Config.get().getBlockExplorer()));
                }
            }
        });

        if(config.getBlockExplorer() != null) {
            blockExplorers.setValue(config.getBlockExplorer());
        } else {
            blockExplorers.getSelectionModel().select(0);
        }

        if(config.getExchangeSource() != null) {
            exchangeSource.setValue(config.getExchangeSource());
        } else {
            //Named rather than selected by index. This was select(2), which meant Coingecko only
            //because of where it sat in a list of four; with the BTC sources gone that index is off
            //the end, which selects nothing and writes a null source back to the config.
            //
            //A null source is also what an existing install lands on after this change, because the
            //config still says COINGECKO and that no longer parses to anything. So this branch is
            //the upgrade path, not just the fresh-install one.
            exchangeSource.setValue(ExchangeSource.NEOXA);
            config.setExchangeSource(exchangeSource.getValue());
        }

        exchangeSource.setButtonCell(new ExchangeSourceButtonCell());
        exchangeSource.setCellFactory(_ -> new ExchangeSourceListCell());

        exchangeSource.valueProperty().addListener((observable, oldValue, source) -> {
            config.setExchangeSource(source);
            updateCurrencies(source);
        });

        updateCurrencies(exchangeSource.getSelectionModel().getSelectedItem());

        loadRecentWallets.setSelected(config.isLoadRecentWallets());
        loadRecentWallets.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setLoadRecentWallets(newValue);
            EventManager.get().post(new RequestOpenWalletsEvent());
        });

        validateDerivationPaths.setSelected(config.isValidateDerivationPaths());
        validateDerivationPaths.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setValidateDerivationPaths(newValue);
            System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_SCRIPT_TYPES_PROPERTY, Boolean.toString(!newValue));
            System.setProperty(Wallet.ALLOW_DERIVATIONS_MATCHING_OTHER_NETWORKS_PROPERTY, Boolean.toString(!newValue));
        });

        groupByAddress.setSelected(config.isGroupByAddress());
        includeMempoolOutputs.setSelected(config.isIncludeMempoolOutputs());
        groupByAddress.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setGroupByAddress(newValue);
        });
        includeMempoolOutputs.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setIncludeMempoolOutputs(newValue);
            EventManager.get().post(new IncludeMempoolOutputsChangedEvent());
        });

        notifyNewTransactions.setSelected(config.isNotifyNewTransactions());
        notifyNewTransactions.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setNotifyNewTransactions(newValue);
        });

        //Hidden rather than left looking operative. The update check is off in this build because its
        //feed belongs to the wallet that follows the chain that kept SHA256d, so a switch offering to
        //turn it on would be promising something that cannot happen. The row goes with it, so there is
        //no orphaned label sitting above an empty space.
        checkNewVersions.setSelected(config.isCheckNewVersions());
        checkNewVersions.selectedProperty().addListener((observableValue, oldValue, newValue) -> {
            config.setCheckNewVersions(newValue);
            EventManager.get().post(new VersionCheckStatusEvent(newValue));
        });
        Node updatesRow = checkNewVersions.getParent();
        if(updatesRow != null) {
            updatesRow.setVisible(false);
            updatesRow.setManaged(false);
        }
    }

    private static Server getBlockExplorer(String serverUrl) {
        String url = serverUrl.trim();
        if(url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new Server(url);
    }

    private ObservableList<Server> getBlockExplorerList() {
        List<Server> servers = Arrays.stream(BlockExplorer.values()).map(BlockExplorer::getServer).collect(Collectors.toList());
        if(Config.get().getBlockExplorer() != null && !servers.contains(Config.get().getBlockExplorer())) {
            servers.add(Config.get().getBlockExplorer());
        }
        servers.add(CUSTOM_BLOCK_EXPLORER);
        return FXCollections.observableList(servers);
    }

    private void updateCurrencies(ExchangeSource exchangeSource) {
        ExchangeSource.CurrenciesService currenciesService = new ExchangeSource.CurrenciesService(exchangeSource);
        currenciesService.setOnSucceeded(event -> {
            updateCurrencies(currenciesService.getValue());
        });
        currenciesService.setOnFailed(event -> {
            log.error("Error retrieving currencies", event.getSource().getException());
        });
        currenciesService.start();
    }

    private void updateCurrencies(List<Currency> currencies) {
        fiatCurrency.valueProperty().removeListener(fiatCurrencyListener);

        fiatCurrency.getItems().clear();
        fiatCurrency.getItems().addAll(currencies);

        Currency configCurrency = Config.get().getFiatCurrency();
        //A configured currency that the list does not offer. There is one real source now, and it
        //quotes dollars natively and borrows every other currency from a conversion lookup, so a
        //short list means that lookup failed rather than that the currency was dropped. Falling back
        //to the head of the list here would rewrite a stored preference to dollars because a request
        //timed out, silently and permanently. Leave it alone and say the list is incomplete; the
        //next time the dialog opens with the lookup working, the currency is theirs again.
        boolean unavailable = configCurrency != null && !currencies.contains(configCurrency);

        if(configCurrency != null && !unavailable) {
            fiatCurrency.setDisable(false);
            fiatCurrency.setValue(configCurrency);
        } else if(configCurrency == null && !currencies.isEmpty()) {
            fiatCurrency.setDisable(false);
            fiatCurrency.getSelectionModel().select(0);
            Config.get().setFiatCurrency(fiatCurrency.getValue());
        } else {
            fiatCurrency.setDisable(true);
        }

        currenciesLoadWarning.setVisible(exchangeSource.getValue() != ExchangeSource.NONE && (currencies.isEmpty() || unavailable));

        //Always fire event regardless of previous selection to update rates
        EventManager.get().post(new FiatCurrencySelectedEvent(exchangeSource.getValue(), fiatCurrency.getValue()));

        fiatCurrency.valueProperty().addListener(fiatCurrencyListener);
    }

    private static class FeeRatesSourceListCell extends ListCell<FeeRatesSource> {
        @Override
        protected void updateItem(FeeRatesSource item, boolean empty) {
            super.updateItem(item, empty);
            if(empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(item.toString());
                setGraphic(item.getSVGImage());
                setGraphicTextGap(8.0d);
            }
        }
    }

    private static class BlockExplorerListCell extends ListCell<Server> {
        @Override
        protected void updateItem(Server server, boolean empty) {
            super.updateItem(server, empty);
            if(empty || server == null || server == BlockExplorer.NONE.getServer()) {
                setText("None");
                setGraphic(null);
            } else if(server == CUSTOM_BLOCK_EXPLORER) {
                setText("Custom...");
                setGraphic(null);
            } else {
                setText(server.getHost());
                setGraphic(BlockExplorer.getSVGImage(server));
                setGraphicTextGap(8.0d);
            }
        }
    }

    private static class ExchangeSourceButtonCell extends ListCell<ExchangeSource> {
        @Override
        protected void updateItem(ExchangeSource exchangeSource, boolean empty) {
            super.updateItem(exchangeSource, empty);
            if(exchangeSource == null || empty) {
                setText("");
                setGraphic(null);
            } else {
                setText(exchangeSource.getName());
                setGraphic(exchangeSource.getSVGImage());
                setGraphicTextGap(8.0d);
            }
        }
    }

    private static class ExchangeSourceListCell extends ListCell<ExchangeSource> {
        @Override
        protected void updateItem(ExchangeSource exchangeSource, boolean empty) {
            super.updateItem(exchangeSource, empty);
            if(exchangeSource == null || empty) {
                setText("");
                setGraphic(null);
            } else {
                String text = exchangeSource.getName();
                if(exchangeSource.getDescription() != null && !exchangeSource.getDescription().isEmpty()) {
                    text += " (" + exchangeSource.getDescription() + ")";
                }
                setText(text);
                setGraphic(exchangeSource.getSVGImage());
                setGraphicTextGap(8.0d);
            }
        }
    }
}
