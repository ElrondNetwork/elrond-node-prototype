package network.elrond.account;

import network.elrond.ExpectedExceptionTest;
import network.elrond.service.AppServiceProvider;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;

public class AccountsManagerTest extends ExpectedExceptionTest {
    AccountsManager accountsManager = new AccountsManager();

    @Test
    public void testHasFundsWithNullAccountsShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "Accounts cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.hasFunds(null, "Test", BigInteger.TEN);
    }

    @Test
    public void testHasFundsWithNullAddressShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "AddressString cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.hasFunds(accounts, null, BigInteger.TEN);
    }

    @Test
    public void testHasFunds() throws IOException, ClassNotFoundException {
        Accounts accounts = new Accounts(new AccountsContext());
        AccountAddress test = AccountAddress.fromHexaString("Test");
        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(test, accounts);
        senderAccountState.addToBalance(BigInteger.TEN);
        AppServiceProvider.getAccountStateService().setAccountState(test, senderAccountState, accounts);
        Assert.assertTrue(accountsManager.hasFunds(accounts, "Test", BigInteger.TEN));
        Assert.assertFalse(accountsManager.hasFunds(accounts, "Test", BigInteger.TEN.add(BigInteger.ONE)));
    }

    @Test
    public void testHasCorrectNonceWithNullAccountsShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "Accounts cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.hasCorrectNonce(null, "Test", BigInteger.TEN);
    }

    @Test
    public void testHasCorrectNonceWithNullAddressShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "AddressString cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.hasCorrectNonce(accounts, null, BigInteger.TEN);
    }

    @Test
    public void testHasCorrectNonce() throws IOException, ClassNotFoundException {
        Accounts accounts = new Accounts(new AccountsContext());
        AccountAddress test = AccountAddress.fromHexaString("Test");
        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(test, accounts);
        senderAccountState.setNonce(BigInteger.TEN);
        AppServiceProvider.getAccountStateService().setAccountState(test, senderAccountState, accounts);
        Assert.assertTrue(accountsManager.hasCorrectNonce(accounts, "Test", BigInteger.TEN));
        //TODO: Uncomment this line when hasCorrectNonceImplementation is ready
        //Assert.assertFalse(accountsManager.hasCorrectNonce(accounts, "Test", BigInteger.TEN.add(BigInteger.ONE)));
    }

    @Test
    public void testTransferFundsWithNullAccountsShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "Accounts cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.transferFunds(null, "Sender", "Receiver", BigInteger.TEN, BigInteger.TEN);
    }

    @Test
    public void testTransferFundsWithNullSenderAddressShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "SenderAddressString cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.transferFunds(accounts, null,"Receiver", BigInteger.TEN, BigInteger.TEN);
    }

    @Test
    public void testTransferFundsWithNullReceiverAddressShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "ReceiverAddressString cannot be null");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.transferFunds(accounts, "Sender","", BigInteger.TEN, BigInteger.TEN);
    }

    @Test
    public void testTransferFundsWithNegativeValueShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "Value cannot be negative");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.transferFunds(accounts, "Sender","Receiver", BigInteger.valueOf(-1), BigInteger.TEN);
    }

    @Test
    public void testTransferFundsWithNegativeNonceShouldThrowException() throws IOException, ClassNotFoundException {
        expected(IllegalArgumentException.class, "Nonce cannot be negative");
        Accounts accounts = new Accounts(new AccountsContext());
        accountsManager.transferFunds(accounts, "Sender","Receiver", BigInteger.TEN, BigInteger.valueOf(-1));
    }

    @Test
    public void testTransferFunds() throws IOException, ClassNotFoundException {
        Accounts accounts = new Accounts(new AccountsContext());
        AccountAddress senderAddress = AccountAddress.fromHexaString("Sender");
        AccountState senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(senderAddress, accounts);
        AccountAddress receiverAddress = AccountAddress.fromHexaString("Receiver");
        senderAccountState.setBalance(BigInteger.TEN);
        AppServiceProvider.getAccountStateService().setAccountState(senderAddress, senderAccountState, accounts);
        accountsManager.transferFunds(accounts, "Sender", "Receiver", BigInteger.ONE, BigInteger.ZERO);
        senderAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(senderAddress, accounts);
        Assert.assertTrue(senderAccountState.getBalance().longValue()  == 9);
        AccountState receiverAccountState = AppServiceProvider.getAccountStateService().getOrCreateAccountState(receiverAddress, accounts);
        Assert.assertTrue(receiverAccountState.getBalance().longValue()  == 1);
        Assert.assertTrue(senderAccountState.getNonce().longValue() == 1);
    }
}
