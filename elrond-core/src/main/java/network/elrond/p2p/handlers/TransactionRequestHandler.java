package network.elrond.p2p.handlers;

import network.elrond.application.AppState;
import network.elrond.blockchain.Blockchain;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.data.model.Transaction;
import network.elrond.p2p.RequestHandler;
import network.elrond.p2p.model.P2PRequestMessage;
import network.elrond.service.AppServiceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TransactionRequestHandler implements RequestHandler<Transaction, P2PRequestMessage> {
    private static final Logger logger = LogManager.getLogger(TransactionRequestHandler.class);

    @Override
    public Transaction onRequest(AppState state, P2PRequestMessage data) {
        logger.traceEntry("params: {} {}", state, data);
        data.getKey();
        String transactionHash = (String) data.getKey();
        Blockchain blockchain = state.getBlockchain();
        Transaction transaction = AppServiceProvider.getBlockchainService().getLocal(transactionHash, blockchain, BlockchainUnitType.TRANSACTION);
        if (transaction == null) {
            logger.info("Replying to request: TRANSACTION with hash {} not found", transactionHash);
        } else {
            logger.info("Replying to request: TRANSACTION with hash {} : {}", transactionHash, transaction);
        }

        return logger.traceExit(transaction);
    }
}