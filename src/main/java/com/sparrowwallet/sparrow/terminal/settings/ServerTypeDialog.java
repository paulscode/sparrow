package com.sparrowwallet.sparrow.terminal.settings;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.DialogWindow;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.PublicElectrumServer;
import com.sparrowwallet.sparrow.net.ServerType;
import com.sparrowwallet.sparrow.terminal.SparrowTerminal;

import java.util.List;

public class ServerTypeDialog extends DialogWindow {
    private final RadioBoxList<String> type;

    public ServerTypeDialog() {
        super("Server Type");

        setHints(List.of(Hint.CENTERED));

        Panel mainPanel = new Panel();
        mainPanel.setLayoutManager(new GridLayout(2).setHorizontalSpacing(5));

        //Public servers are withdrawn on this chain: none index it, and connecting to one syncs and then
        //shows another chain's blocks and balances against addresses this wallet derives. Offering the
        //option would lead to a picker with nothing in it, and the default below would have selected it.
        ServerType[] serverTypes = PublicElectrumServer.supportedNetwork()
                ? new ServerType[] { ServerType.PUBLIC_ELECTRUM_SERVER, ServerType.BITCOIN_CORE, ServerType.ELECTRUM_SERVER }
                : new ServerType[] { ServerType.BITCOIN_CORE, ServerType.ELECTRUM_SERVER };

        mainPanel.addComponent(new Label("Connect using"));
        type = new RadioBoxList<>();
        for(ServerType serverType : serverTypes) {
            type.addItem(serverType.getName());
        }

        //Never a type that is not on offer. Defaulting an unconfigured install to the public option put
        //it on a server type that cannot connect at all, quietly, and left the radio list with nothing
        //checked because that name is no longer in it.
        if(Config.get().getServerType() == null || !List.of(serverTypes).contains(Config.get().getServerType())) {
            Config.get().setServerType(serverTypes[0]);
        }
        type.setCheckedItem(Config.get().getServerType().getName());
        type.addListener((selectedIndex, previousSelection) -> {
            if(selectedIndex != previousSelection) {
                Config.get().setServerType(serverTypes[selectedIndex]);
            }
        });
        mainPanel.addComponent(type);

        Panel buttonPanel = new Panel();
        buttonPanel.setLayoutManager(new GridLayout(1).setHorizontalSpacing(1));
        buttonPanel.addComponent(new Button("Continue", this::onContinue));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        buttonPanel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER,false,false)).addTo(mainPanel);
        setComponent(mainPanel);
    }

    private void onContinue() {
        close();

        if(Config.get().getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER) {
            PublicElectrumDialog publicElectrumServer = new PublicElectrumDialog();
            publicElectrumServer.showDialog(SparrowTerminal.get().getGui());
        } else if(Config.get().getServerType() == ServerType.BITCOIN_CORE) {
            BitcoinCoreDialog bitcoinCoreDialog = new BitcoinCoreDialog();
            bitcoinCoreDialog.showDialog(SparrowTerminal.get().getGui());
        } else if(Config.get().getServerType() == ServerType.ELECTRUM_SERVER) {
            PrivateElectrumDialog privateElectrumDialog = new PrivateElectrumDialog();
            privateElectrumDialog.showDialog(SparrowTerminal.get().getGui());
        }
    }
}
