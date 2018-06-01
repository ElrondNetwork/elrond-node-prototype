package network.elrond.data;

import network.elrond.Application;
import network.elrond.account.Accounts;
import network.elrond.application.AppState;
import network.elrond.core.Util;
import network.elrond.crypto.MultiSignatureService;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.service.AppServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class AppBlockManager {


    private Logger logger = LoggerFactory.getLogger(AppBlockManager.class);

    private static AppBlockManager instance = new AppBlockManager();

    public static AppBlockManager instance() {
        return instance;
    }

    public Block composeBlock(List<Transaction> transactions, Application application) {

        Util.check(transactions!=null, "transactions!=null");
        Util.check(application!=null, "application!=null");

        AppState state = application.getState();
        Accounts accounts = state.getAccounts();

        Block block = getNewBlockAndBindToPrevious(state.getCurrentBlock());
        addTransactions(transactions, accounts, block);
        block.setAppStateHash(accounts.getAccountsPersistenceUnit().getRootHash());
        AppServiceProvider.getAccountStateService().rollbackAccountStates(accounts);

        return block;
    }

    private void addTransactions(List<Transaction> transactions, Accounts accounts, Block block) {
        for (Transaction transaction : transactions) {
            boolean valid = AppServiceProvider.getTransactionService().verifyTransaction(transaction);
            if (!valid) {
                logger.info("Invalid transaction discarded [verify] " + transaction);
                continue;
            }

            ExecutionReport executionReport = AppServiceProvider.getExecutionService().processTransaction(transaction, accounts);
            if (!executionReport.isOk()) {
                logger.info("Invalid transaction discarded [exec] " + transaction);
                continue;
            }

            byte[] txHash = AppServiceProvider.getSerializationService().getHash(transaction);
            block.getListTXHashes().add(txHash);
        }
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

        boolean sigOk = multiSignatureService.verifyAggregatedSignature(signersPublicKeys,aggregatedSignature, aggregatedCommitment, blockHashNoSig, 1 );
        block.setSignature(aggregatedSignature);
        block.setCommitment(aggregatedCommitment);
    }
}
