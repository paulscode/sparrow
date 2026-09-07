package com.sparrowwallet.sparrow.terminal.settings;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.net.PublicElectrumServer;

import java.util.List;

public class PublicElectrumDialog extends ServerProxyDialog {
    private final ComboBox<PublicElectrumServer> url;

    public PublicElectrumDialog() {
        super("Public Electrum");

        setHints(List.of(Hint.CENTERED));

        Panel mainPanel = new Panel(new GridLayout(3).setHorizontalSpacing(2).setVerticalSpacing(0));
        //No public server indexes this chain, so this screen has nothing to offer. Say that, rather than
        //draw an empty picker: it is reachable from the proxy screen without passing the server type
        //screen that would otherwise have moved an old config off this option.
        boolean available = PublicElectrumServer.supportedNetwork();

        mainPanel.addComponent(new Label(available ? "Warning!" : "Unavailable"));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new Label(available
                        ? "Using a public server means it can see your transactions"
                        : "No public server follows this chain. Use Bitcoin Core or a private Electrum server."),
                GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.CENTER,true,false, 3, 1));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        mainPanel.addComponent(new Label("URL"));
        url = new ComboBox<>();
        for(PublicElectrumServer server : PublicElectrumServer.getServers()) {
            url.addItem(server);
        }
        if(available) {
            if(Config.get().getPublicElectrumServer() == null) {
                AppServices.get().changePublicServer();
            }
            url.setSelectedItem(PublicElectrumServer.fromServer(Config.get().getPublicElectrumServer()));
            url.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> {
                if(selectedIndex != previousSelection) {
                    //Indexes the same list the combo was filled from, so it must not run against an empty one
                    List<PublicElectrumServer> servers = PublicElectrumServer.getServers();
                    if(selectedIndex >= 0 && selectedIndex < servers.size()) {
                        Config.get().setPublicElectrumServer(servers.get(selectedIndex).getServer());
                    }
                }
            });
        }
        mainPanel.addComponent(url);
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        addProxyComponents(mainPanel);

        Panel buttonPanel = new Panel();
        buttonPanel.setLayoutManager(new GridLayout(2).setHorizontalSpacing(1));
        buttonPanel.addComponent(new Button("Test", this::onTest));
        buttonPanel.addComponent(new Button("Done", this::onDone));

        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));
        mainPanel.addComponent(new EmptySpace(TerminalSize.ONE));

        buttonPanel.setLayoutData(GridLayout.createLayoutData(GridLayout.Alignment.END, GridLayout.Alignment.CENTER,false,false)).addTo(mainPanel);
        setComponent(mainPanel);
    }
}
