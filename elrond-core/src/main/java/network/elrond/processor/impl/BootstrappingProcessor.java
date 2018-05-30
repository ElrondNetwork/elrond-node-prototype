package network.elrond.processor.impl;

import network.elrond.Application;
import network.elrond.application.AppContext;
import network.elrond.application.AppState;
import network.elrond.blockchain.BlockchainService;
import network.elrond.blockchain.BlockchainUnitType;
import network.elrond.blockchain.SettingsType;
import network.elrond.core.Util;
import network.elrond.data.*;
import network.elrond.p2p.P2PBroadcastChanel;
import network.elrond.p2p.P2PObjectService;
import network.elrond.processor.AppTask;
import network.elrond.processor.AppTasks;
import network.elrond.service.AppServiceProvider;
import org.bouncycastle.util.encoders.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;

public class BootstrappingProcessor implements AppTask {

    private Logger logger = LoggerFactory.getLogger(AppTasks.class);

    private BootstrapService bootstrapService = AppServiceProvider.getBootstrapService();
    private BlockchainService blockchainService = AppServiceProvider.getBlockchainService();
    private BlockchainService appPersistanceService = AppServiceProvider.getAppPersistanceService();
    private TransactionService transactionService = AppServiceProvider.getTransactionService();
    private P2PObjectService p2PObjectService = AppServiceProvider.getP2PObjectService();
    private SerializationService serializationService = AppServiceProvider.getSerializationService();

    @Override
    public void process(Application application) throws IOException {
        AppState state = application.getState();

        AppContext context = application.getContext();

        Thread threadProcess = new Thread(() -> {

            BigInteger maxBlkHeightNetw = Util.BIG_INT_MIN_ONE;
            BigInteger maxBlkHeightLocal = Util.BIG_INT_MIN_ONE;

            while (state.isStillRunning()) {
                maxBlkHeightNetw = Util.BIG_INT_MIN_ONE;
                maxBlkHeightLocal = Util.BIG_INT_MIN_ONE;

                //rules:
                //    when blockchain_local < 0 AND blockchain_network < 0 => start from scratch
                //    when blockchain_network > 0 => bootstrapping
                //    when blockchain_local >= 0 AND blockchain_network < 0 => start from scratch OR rebuild from disk

                try {
                    maxBlkHeightNetw = bootstrapService.getMaxBlockSizeNetwork(state.getConnection());
                } catch (Exception ex) {
                    //ex.printStackTrace();
                }

                try {
                    maxBlkHeightLocal = bootstrapService.getMaxBlockSizeLocal(state.getBlockchain());
                } catch (Exception ex) {
                    //ex.printStackTrace();
                }

                ExecutionReport exReport = new ExecutionReport();

                if ((maxBlkHeightLocal.compareTo(BigInteger.ZERO) < 0) && (maxBlkHeightNetw.compareTo(BigInteger.ZERO) < 0)) {
                    exReport.combine(startFromScratch(application));
                } else if (maxBlkHeightNetw.compareTo(BigInteger.ZERO) > 0) {
                    exReport.combine(bootstrap(application, maxBlkHeightLocal, maxBlkHeightNetw));
                } else if ((maxBlkHeightLocal.compareTo(BigInteger.ZERO) >= 0) && (maxBlkHeightNetw.compareTo(BigInteger.ZERO) < 0)) {
                    if (context.getBootstrapType() == BootstrapType.START_FROM_SCRATCH) {
                        exReport.combine(startFromScratch(application));
                    } else if (context.getBootstrapType() == BootstrapType.REBUILD_FROM_DISK) {
                        exReport.combine(rebuildFromDisk(application, maxBlkHeightLocal));
                    } else {
                        exReport.combine(new ExecutionReport().ko("Can not bootstrap! Unknown BootstrapType : " +
                                context.getBootstrapType().toString() + "!"));
                    }
                }

                logger.info("Nothing else to bootstrap! Waiting 5 seconds...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
        });
        threadProcess.start();
    }

    ExecutionReport startFromScratch(Application application) {
        ExecutionReport result = new ExecutionReport();
        AppState state = application.getState();

        result.combine(new ExecutionReport().ok("Start from scratch..."));
        GenesisBlock gb = new GenesisBlock(context.getStrAddressMint(), context.getValueMint());
        String strHashGB = new String(Base64.encode(serializationService.getHash(gb, true)));

        //put locally not broadcasting it
        try {
            result.combine(putBlockInBlockchain(gb, strHashGB, state));
            bootstrapService.setMaxBlockSizeNetwork(BigInteger.ZERO, state.getConnection());
            bootstrapService.setMaxBlockSizeLocal(state.getBlockchain(), BigInteger.ZERO);

            ExecutionReport exExecuteBlock = AppServiceProvider.getExecutionService().processBlock(gb, state.getAccounts(), state.getBlockchain());
            result.combine(exExecuteBlock);

            if (result.isOk()){
                result.combine(new ExecutionReport().ok("Start from scratch...OK!"));
            }

        } catch (Exception ex) {
            result.combine(new ExecutionReport().ko(ex));
        }

        return (result);
    }

    ExecutionReport bootstrap(Application application, BigInteger maxBlkHeightLocal, BigInteger maxBlkHeightNetw) {
        ExecutionReport result = new ExecutionReport();
        AppState state = application.getState();



        result.combine(new ExecutionReport().ok("Bootstrapping... [local height: " + maxBlkHeightLocal.toString(10) + " > network height: " +
                maxBlkHeightNetw.toString(10) + "..."));
        state.setBootstrapping(true);

        String strHashBlock;
        Block blk;

        for (BigInteger counter = maxBlkHeightLocal.add(BigInteger.ONE); counter.compareTo(maxBlkHeightNetw) <= 0; counter = counter.add(BigInteger.ONE)) {
            //get the hash of the block from network
            try {
                strHashBlock = bootstrapService.getBlockHashFromHeightNetwork(counter, state.getConnection());
            } catch (Exception ex) {
                result.ko(ex);
                return (result);
            }

            if (strHashBlock == null) {
                result.ko("Can not bootstrap! Could not find block with nonce = " + counter.toString(10) + " on DTH!");
                return(result);
            }

            try {
                blk = p2PObjectService.getJSONdecoded(strHashBlock, state.getConnection(), Block.class);
            } catch (Exception ex) {
                result.ko(ex);
                return (result);
            }

            if (blk == null) {
                result.ko("Can not find block hash " + strHashBlock + " on DHT!");
                break;
            }

            ExecutionReport exExecuteBlock = AppServiceProvider.getExecutionService().processBlock(blk, state.getAccounts(), state.getBlockchain());

            result.combine(exExecuteBlock);

            if (!result.isOk()){
                return (result);
            }

            //block successfully processed, add it to blockchain structure
            result.combine(putBlockInBlockchain(blk, strHashBlock, state));

            if (!result.isOk()){
                return (result);
            }
        }

        return(result);
    }

    ExecutionReport rebuildFromDisk(Application application, BigInteger maxBlkHeightLocal) {
        ExecutionReport result = new ExecutionReport();
        AppState state = application.getState();



        result.combine(new ExecutionReport().ok("Start bootstrapping by loading from disk..."));
        state.setBootstrapping(true);
        //start pushing blocks and transactions
        //block with nonce = 0 is genesis and does not broadcast, it's autogenerated
        for (BigInteger counter = BigInteger.valueOf(1); counter.compareTo(maxBlkHeightLocal) <= 0; counter = counter.add(BigInteger.ONE)) {
            try {
                result.combine(new ExecutionReport().ok("Put block with height: " + counter.toString(10) + "..."));
                //put block
                String strHashBlk = bootstrapService.getBlockHashFromHeightLocal(state.getBlockchain(), counter);
                Block blk = blockchainService.get(strHashBlk, state.getBlockchain(), BlockchainUnitType.BLOCK);
                p2PObjectService.putJSONencoded(blk, strHashBlk, state.getConnection());

                //put pair block_height - block hash
                bootstrapService.setBlockHashFromHeightNetwork(counter, strHashBlk, state.getConnection());

                //put transactions
                for (int j = 0; j < blk.getListTXHashes().size(); j++) {
                    String strHashTx = new String(Base64.encode(blk.getListTXHashes().get(j)));

                    Transaction tx = blockchainService.get(strHashTx, state.getBlockchain(), BlockchainUnitType.TRANSACTION);

                    p2PObjectService.putJSONencoded(tx, strHashTx, state.getConnection());
                }

                //put settings max_block_height
                bootstrapService.setMaxBlockSizeNetwork(counter, state.getConnection());
            } catch (Exception ex) {
                result.ko(ex);
                return (result);
            }
        }

        return(result);
    }

    ExecutionReport putBlockInBlockchain(Block blk, String strBlockHash, AppState state) {
        ExecutionReport result = new ExecutionReport();

        try {
            appPersistanceService.put(strBlockHash, blk, state.getBlockchain(), BlockchainUnitType.BLOCK);
            bootstrapService.setBlockHashFromHeightLocal(state.getBlockchain(), blk.getNonce(), strBlockHash);
            appPersistanceService.put(blk.getNonce(), strBlockHash, state.getBlockchain(), BlockchainUnitType.BLOCK_INDEX);
            bootstrapService.setMaxBlockSizeLocal(state.getBlockchain(), blk.getNonce());
            result.combine(new ExecutionReport().ok("Put block in blockchain with hash: " + strBlockHash));
        } catch (Exception ex) {
            result.combine(new ExecutionReport().ko(ex));
        }

        return (result);
    }

}

