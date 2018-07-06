package network.elrond;

import network.elrond.account.AccountAddress;
import network.elrond.application.AppContext;
import network.elrond.core.ResponseObject;
import network.elrond.core.ThreadUtil;
import network.elrond.core.Util;
import network.elrond.crypto.PublicKey;
import network.elrond.data.BootstrapType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SeedNodeRunner {
    private static final Logger logger = LogManager.getLogger(SeedNodeRunner.class);

    public static void main(String[] args) throws Exception {
        SimpleDateFormat sdfSource = new SimpleDateFormat(
                "yyyy-MM-dd HH.mm.ss");
        Util.changeLogsPath("logs/" + Util.getHostName() + " - " + sdfSource.format(new Date()));

        String nodeName = "elrond-node-1";
        Integer port = 4000;
        Integer masterPeerPort = 4000;
        String masterPeerIpAddress = "127.0.0.1";
        String seedNodeRunnerPrivateKey = "1111111111111111fa612ecafcfd145cc06c1fb64d7499ef34696ff16b82cbc1";

        AppContext context = ContextCreator.createAppContext(nodeName, seedNodeRunnerPrivateKey, masterPeerIpAddress, masterPeerPort, port,
                BootstrapType.START_FROM_SCRATCH, nodeName);

        ElrondFacade facade = new ElrondFacadeImpl();

        Application application = facade.start(context);


        Thread thread = new Thread(() -> {

            do {
                PublicKey key = application.getState().getPublicKey();
                AccountAddress address = AccountAddress.fromHexString(Util.TEST_ADDRESS);
                //AccountAddress address = AccountAddress.fromHexString(Util.getAddressFromPublicKey(key.getValue()));
                ResponseObject responseObjectTransaction = facade.send(address, BigInteger.TEN, application);
                if (responseObjectTransaction.isSuccess()){
                    ResponseObject sendersBalance = facade.getBalance(AccountAddress.fromBytes(application.getState().getPublicKey().getValue()), application);
                    ResponseObject receiverBalance = facade.getBalance(address, application);
                    logger.info("Sender balance: {}, receiver balance: {}", sendersBalance.getPayload(), receiverBalance.getPayload());

//                    String hash = AppServiceProvider.getSerializationService().getHashString(responseObjectTransaction.getPayload().toString());
//                    ResponseObject responseObjectReceipt = facade.getReceipt(hash, application);
//                    logger.info(responseObjectReceipt);
                }

                ThreadUtil.sleep(1);
            } while (true);

        });
        thread.start();

    }
}
