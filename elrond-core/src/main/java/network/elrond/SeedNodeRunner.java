package network.elrond;

import network.elrond.account.AccountAddress;
import network.elrond.application.AppContext;
import network.elrond.core.ThreadUtil;
import network.elrond.core.Util;
import network.elrond.crypto.PrivateKey;
import network.elrond.crypto.PublicKey;
import network.elrond.data.BootstrapType;
import network.elrond.data.Receipt;
import network.elrond.data.Transaction;
import network.elrond.service.AppServiceProvider;

import java.math.BigInteger;

public class SeedNodeRunner {

    public static void main(String[] args) throws Exception {

        String nodeName = "elrond-node-1";
        Integer port = 40010;
        Integer masterPeerPort = 40010;
        String masterPeerIpAddress = "192.168.11.51";
        String seedNodeRunnerPrivateKey = "1111111111111111fa612ecafcfd145cc06c1fb64d7499ef34696ff16b82cbc2";

        PublicKey pbKey = new PublicKey(new PrivateKey(seedNodeRunnerPrivateKey));

        AppContext context = ContextCreator.createAppContext(nodeName, seedNodeRunnerPrivateKey, masterPeerIpAddress, masterPeerPort, port,
                BootstrapType.REBUILD_FROM_DISK, nodeName, Util.VALUE_MINTING);

        ElrondFacade facade = new ElrondFacadeImpl();

        Application application = facade.start(context);


        Thread thread = new Thread(() -> {

            do {

                AccountAddress address = AccountAddress.fromPublicKey(pbKey);
                Transaction transaction = facade.send(address, BigInteger.TEN, application);
                System.out.println(facade.getBalance(address, application));

                if (transaction != null) {
                    String hash = AppServiceProvider.getSerializationService().getHashString(transaction);
                    Receipt receipt = facade.getReceipt(hash, application);
                    System.out.println(receipt);
                }

                ThreadUtil.sleep(1000);
            } while (true);

        });
        thread.start();

    }
}
