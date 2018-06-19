package network.elrond.data;

import network.elrond.account.Accounts;
import network.elrond.application.AppState;
import network.elrond.blockchain.Blockchain;
import network.elrond.blockchain.BlockchainService;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.chronology.NTPClient;
import network.elrond.chronology.Round;
import network.elrond.core.Util;
import network.elrond.crypto.MultiSignatureService;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.p2p.P2PBroadcastChanel;
import network.elrond.p2p.P2PChannelName;
import network.elrond.service.AppServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class AppBlockManager {


    private Logger logger = LoggerFactory.getLogger(AppBlockManager.class);

    private static AppBlockManager instance = new AppBlockManager();

    public static AppBlockManager instance() {
        return instance;
    }


    public void generateAndBroadcastBlock(List<String> hashes, PrivateKey privateKey, AppState state) {

        Accounts accounts = state.getAccounts();
        Blockchain blockchain = state.getBlockchain();


        BlockchainService blockchainService = AppServiceProvider.getBlockchainService();

        try {

            List<Transaction> transactions = blockchainService.getAll(hashes, blockchain, BlockchainUnitType.TRANSACTION);
            Block block = composeBlock(transactions, state);


            AppBlockManager.instance().signBlock(block, privateKey);
            ExecutionService executionService = AppServiceProvider.getExecutionService();
            ExecutionReport result = executionService.processBlock(block, accounts, blockchain);

            if (result.isOk()) {

                String hashBlock = AppServiceProvider.getSerializationService().getHashString(block);
                AppServiceProvider.getBootstrapService().commitBlock(block, hashBlock, blockchain);

                logger.info("New block proposed" + hashBlock);
            }


        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }


    public Block composeBlock(List<Transaction> transactions,  AppState state) throws IOException {

        Util.check(state!=null, "state!=null");

        Accounts accounts = state.getAccounts();
        Blockchain blockchain = state.getBlockchain();
        NTPClient ntpClient = state.getNtpClient();


        Util.check(transactions!=null, "transactions!=null");
        Util.check(blockchain!=null, "blockchain!=null");
        Util.check(accounts!=null, "accounts!=null");
        Util.check(blockchain.getGenesisBlock()!=null, "genesisBlock!=null");

        Block block = getNewBlockAndBindToPrevious(blockchain.getCurrentBlock());
        //compute round and round start millis = calculated round start millis

        long synchronizedTime = AppServiceProvider.getChronologyService().getSynchronizedTime(ntpClient);
        long timestamp = blockchain.getGenesisBlock().getTimestamp();
        Round round = AppServiceProvider.getChronologyService().getRoundFromDateTime(timestamp, synchronizedTime);
        block.setRoundIndex(round.getIndex());
        block.setTimestamp(round.getStartRoundMillis());

        addTransactions(transactions, block, state);
        block.setAppStateHash(accounts.getAccountsPersistenceUnit().getRootHash());
        AppServiceProvider.getAccountStateService().rollbackAccountStates(accounts);

        return block;
    }

    private void addTransactions(List<Transaction> transactions, Block block, AppState state) throws IOException {

        Accounts accounts = state.getAccounts();

        for (Transaction transaction : transactions) {
            boolean valid = AppServiceProvider.getTransactionService().verifyTransaction(transaction);
            if (!valid) {
                rejectTransaction(transaction, state);
                logger.info("Invalid transaction discarded [verify] " + transaction);
                continue;
            }

            ExecutionReport executionReport = AppServiceProvider.getExecutionService().processTransaction(transaction, accounts);
            if (!executionReport.isOk()) {
                rejectTransaction(transaction, state);
                logger.info("Invalid transaction discarded [exec] " + transaction);
                continue;
            }

            byte[] txHash = AppServiceProvider.getSerializationService().getHash(transaction);
            acceptTransaction(transaction, state);

            block.getListTXHashes().add(txHash);
        }
    }

    private void acceptTransaction(Transaction transaction, AppState state) throws IOException {

        ReceiptStatus status = ReceiptStatus.ACCEPTED;
        String log = "Transaction processed";

        sendReceipt(transaction, log, status, state);
    }

    private void sendReceipt(Transaction transaction, String log, ReceiptStatus status, AppState state) throws IOException {

        String transactionHash = AppServiceProvider.getSerializationService().getHashString(transaction);

        Receipt receipt = new Receipt(transactionHash, status, log);
        String receiptHash = AppServiceProvider.getSerializationService().getHashString(receipt);

        // Store on blockchain
        Blockchain blockchain = state.getBlockchain();
        AppServiceProvider.getBlockchainService().put(transactionHash, receiptHash, blockchain, BlockchainUnitType.TRANSACTION_RECEIPT);
        AppServiceProvider.getBlockchainService().put(receiptHash, receipt, blockchain, BlockchainUnitType.RECEIPT);

        // Broadcast
        P2PBroadcastChanel channel = state.getChanel(P2PChannelName.RECEIPT);
        AppServiceProvider.getP2PBroadcastService().publishToChannel(channel, receiptHash);
    }

    private void rejectTransaction(Transaction transaction, AppState state) throws IOException {

        ReceiptStatus status = ReceiptStatus.REJECTED;
        String log = "Invalid transaction";

        sendReceipt(transaction, log, status, state);

    }

    private Block getNewBlockAndBindToPrevious(Block currentBlock) {
        Block block = new Block();
        byte[] hash = AppServiceProvider.getSerializationService().getHash(currentBlock);

        // Bind on prev block
        block.setPrevBlockHash(hash);
        BigInteger nonce = currentBlock.getNonce().add(BigInteger.ONE);
        block.setNonce(nonce);
        return block;
    }

    public void signBlock(Block block, PrivateKey privateKey){
        Util.check(block != null, "block != null");
        Util.check(privateKey != null, "application != null");

        //AppContext context = application.getContext();

        block.listPubKeys.clear();
        block.listPubKeys.add(Util.byteArrayToHexString(new PublicKey(privateKey).getValue()));
        block.setCommitment(null);
        block.setSignature(null);

        ArrayList<byte[]> signersPublicKeys = new ArrayList<>();
        ArrayList<byte[]> commitmentSecrets = new ArrayList<>();
        ArrayList<byte[]> commitments = new ArrayList<>();
        ArrayList<byte[]> challenges = new ArrayList<>();
        ArrayList<byte[]> signatureShares = new ArrayList<>();
        byte[] aggregatedCommitment;
        byte[] aggregatedSignature = new byte[0];
        int sizeConsensusGroup = 1;
        MultiSignatureService multiSignatureService = AppServiceProvider.getMultiSignatureService();

        byte[][] result = new byte[2][];

        for (int i = 0; i < sizeConsensusGroup; i++) {
            signersPublicKeys.add(new PublicKey(privateKey).getValue());
            commitmentSecrets.add(multiSignatureService.computeCommitmentSecret());
            commitments.add(multiSignatureService.computeCommitment(commitmentSecrets.get(i)));
        }

        byte[] blockHashNoSig = AppServiceProvider.getSerializationService().getHash(block);

        // aggregate the commitments
        aggregatedCommitment = multiSignatureService.aggregateCommitments(commitments, 1);

        // compute challenges and signatures for each signer
        for (int i = 0; i < sizeConsensusGroup; i++) {
            if (0 != ((1 << i) & 1)) {
                challenges.add(
                        multiSignatureService.computeChallenge(
                                signersPublicKeys,
                                signersPublicKeys.get(i),
                                aggregatedCommitment,
                                blockHashNoSig,
                                1
                        )
                );

                // compute signature shares
                signatureShares.add(
                        multiSignatureService.computeSignatureShare(
                                challenges.get(i),
                                privateKey.getValue(),
                                commitmentSecrets.get(i)
                        )
                );
            } else {
                challenges.add(new byte[0]);
                signatureShares.add(new byte[0]);
            }

            aggregatedSignature = multiSignatureService.aggregateSignatures(signatureShares, 1);
        }

        //boolean sigOk = multiSignatureService.verifyAggregatedSignature(signersPublicKeys,aggregatedSignature, aggregatedCommitment, blockHashNoSig, 1 );
        block.setSignature(aggregatedSignature);
        block.setCommitment(aggregatedCommitment);
    }
}
