package com.sparrowwallet.sparrow.terminal.wallet.table;

import com.sparrowwallet.drongo.wallet.Status;
import com.sparrowwallet.sparrow.control.MaturityEstimate;
import com.sparrowwallet.sparrow.wallet.Entry;
import com.sparrowwallet.sparrow.wallet.TransactionEntry;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

public class DateTableCell extends TableCell {
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm");
    public static final int TRANSACTION_WIDTH = 23;
    public static final int UTXO_WIDTH = 18;

    public DateTableCell(Entry entry) {
        super(entry);
    }

    @Override
    public String formatCell() {
        String unselected = formatUnselectedCell();

        if(selected) {
            return "(*) " + unselected.substring(Math.min(4, unselected.length()));
        }

        if(entry instanceof UtxoEntry utxoEntry && utxoEntry.getHashIndex().getStatus() == Status.FROZEN) {
            return "(f) " + unselected.substring(Math.min(4, unselected.length()));
        }

        return unselected;
    }

    public String formatUnselectedCell() {
        if(entry instanceof TransactionEntry transactionEntry && transactionEntry.getBlockTransaction() != null) {
            if(transactionEntry.getBlockTransaction().getHeight() == -1) {
                return "Unconfirmed Parent";
            } else if(transactionEntry.getBlockTransaction().getHeight() == 0) {
                return "Unconfirmed";
            } else {
                return DATE_FORMAT.format(transactionEntry.getBlockTransaction().getDate());
            }
        } else if(entry instanceof UtxoEntry utxoEntry && utxoEntry.getBlockTransaction() != null) {
            if(utxoEntry.getBlockTransaction().getHeight() == -1) {
                return "Unconfirmed Parent";
            } else if(utxoEntry.getBlockTransaction().getHeight() == 0) {
                return "Unconfirmed";
            } else if(utxoEntry.isImmatureCoinbase()) {
                //Ahead of the date, which would otherwise be all this column said about a coin that cannot
                //be spent. The short form of the estimate, because this column is a fixed eighteen
                //characters and widening it would push the UTXO table past an eighty column terminal. If
                //even the short form does not fit, the duration goes rather than the fact.
                String immature = "Immature " + MaturityEstimate.describeShort(utxoEntry.getBlocksUntilMature());
                return immature.length() <= UTXO_WIDTH ? immature : "Immature";
            } else {
                return DATE_FORMAT.format(utxoEntry.getBlockTransaction().getDate());
            }
        }

        return "";
    }
}
