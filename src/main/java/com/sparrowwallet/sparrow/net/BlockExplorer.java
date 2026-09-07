package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.io.Server;
import org.girod.javafx.svgimage.SVGImage;
import org.girod.javafx.svgimage.SVGLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Locale;

/**
 * Where a txid is opened. Only None is offered.
 *
 * <p>mempool.space and blockstream.info both index the chain that kept SHA256d. A txid from this
 * chain is either absent there, which is merely useless, or present because the transaction was
 * replayed, which is worse: the explorer would then show a confirmation on the other chain as
 * though it were this one's. A custom URL is still accepted for anyone running an explorer that
 * follows this chain.
 */
public enum BlockExplorer {
    NONE("http://none");

    private static final Logger log = LoggerFactory.getLogger(BlockExplorer.class);

    private final Server server;

    BlockExplorer(String url) {
        this.server = new Server(url);
    }

    public Server getServer() {
        return server;
    }

    public static SVGImage getSVGImage(Server server) {
        try {
            URL url = AppServices.class.getResource("/image/blockexplorer/" + server.getHost().toLowerCase(Locale.ROOT) + "-icon.svg");
            if(url != null) {
                return SVGLoader.load(url);
            }
        } catch(Exception e) {
            log.error("Could not load block explorer image for " + server.getHost());
        }

        return null;
    }
}
