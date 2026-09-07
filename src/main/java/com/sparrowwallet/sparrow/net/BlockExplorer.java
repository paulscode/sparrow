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
 * Where a txid is opened.
 *
 * <p>mempool.space and blockstream.info both index the chain that kept SHA256d, and are gone. A txid
 * from this chain is either absent there, which is merely useless, or present because the transaction
 * was replayed, which is worse: the explorer would then show a confirmation on the other chain as
 * though it were this one's.
 *
 * <p>mempool.guide follows this chain, which was checked rather than taken on trust: its block at
 * height 968000 hashes to 0000000000000001b3afd284d1fc59c26ced6b9589a71d565fec8c8f1234589d, which is
 * what a node on this chain has there, and mempool.space does not carry that height at all. It runs
 * the mempool.space codebase, so a transaction is at /tx/&lt;txid&gt;, which is what a URL carrying no
 * {0} placeholder is turned into.
 *
 * <p>A custom URL remains available for anyone running their own.
 */
public enum BlockExplorer {
    MEMPOOL_GUIDE("https://mempool.guide"),
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
