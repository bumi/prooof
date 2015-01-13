package prooof;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;

import org.bitcoinj.core.AbstractWalletEventListener;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DownloadListener;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.Wallet.SendRequest;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;

public class Treasury {
    public NetworkParameters params;
    public static String opReturnIdentifier = "PROOOF1-";
    public Wallet wallet;
    private File chainFile;
    private SPVBlockStore chainStore;
    public BlockChain blockChain;
    private PeerGroup peerGroup;
    public File walletFile;
    private boolean useLocalhost;
    static Logger logger = LoggerFactory.getLogger(Treasury.class.getName());

    public Treasury(String environment) throws Exception {
        this(environment, false);
    }

    public Treasury(String environment, boolean useLocalhost) throws Exception {
        this(environment, new File("."), useLocalhost);
    }

    public Treasury(String environment, File directory, boolean useLocalhost) throws Exception {
        this.params = this.paramsForEnvironment(environment);
        this.chainFile = new File(directory, "prooof-" + environment + ".spvchain");
        this.useLocalhost = useLocalhost;
        this.chainStore = new SPVBlockStore(params, this.chainFile);
        this.blockChain = new BlockChain(params, this.chainStore);
        this.peerGroup = new PeerGroup(params, this.blockChain);
    }

    public void loadWalletFromFile(File walletFile) throws Exception {
        this.walletFile = walletFile;
        if (walletFile.exists()) {
            this.wallet = Wallet.loadFromFile(this.walletFile);
        } else {
            this.wallet = new Wallet(this.params);
            this.wallet.saveToFile(walletFile);
        }
        this.wallet.autosaveToFile(this.walletFile, 300, TimeUnit.MILLISECONDS, null);
        this.wallet.addEventListener(new Treasury.WalletListener());
        this.blockChain.addWallet(this.wallet);
        this.peerGroup.addWallet(this.wallet);
    }

    public void start() throws Exception {
        if (this.useLocalhost) {
            InetAddress localhost = InetAddress.getLocalHost();
            PeerAddress localPeer = new PeerAddress(localhost, this.params.getPort());
            this.peerGroup.addAddress(localPeer);
        } else {
            this.peerGroup.addPeerDiscovery(new DnsDiscovery(this.params));
        }

        DownloadListener bListener = new DownloadListener() {
            @Override
            public void doneDownload() {
                logger.info("blockchain downloaded");
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    logger.info("shutting down");
                    wallet.saveToFile(walletFile);
                    logger.info("saved all wallets, BYE");
                } catch (Exception e) {
                    logger.error("error saving wallet");
                }
            }
        });
        this.peerGroup.startAsync();
        this.peerGroup.awaitRunning();
        this.peerGroup.startBlockChainDownload(bListener);
        //bListener.await();
    }

    public boolean save() {
        try {
            this.wallet.saveToFile(this.walletFile);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Wallet.SendRequest createAndPublishOpReturn(String data) throws InsufficientMoneyException {
        String opData = Treasury.opReturnIdentifier + data;
        Transaction tx = new Transaction(this.params);
        Script script = ScriptBuilder.createOpReturnScript(opData.getBytes());
        tx.addOutput(Coin.ZERO, script);
        SendRequest req = Wallet.SendRequest.forTx(tx);

        logger.info("completing transaction");
        this.wallet.completeTx(req);
        logger.info("committing transaction");
        this.wallet.maybeCommitTx(req.tx);
        logger.info("broadcasting transaction");
        this.peerGroup.broadcastTransaction(req.tx);
        return req;
    }

    public Transaction fetchTransaction(String txHash) {
        Peer peer = this.peerGroup.getConnectedPeers().get(0);
        Sha256Hash txShaHash = new Sha256Hash(txHash);
        ListenableFuture<Transaction> future = peer.getPeerMempoolTransaction(txShaHash);
        logger.info("Waiting for node to send us the requested transaction: " + txHash);

        ;
        try {
            Transaction tx = future.get(1, TimeUnit.SECONDS);
            return tx;
        } catch (Exception e) {
            logger.error("faild to get transaction: " + txHash, e);
            e.printStackTrace();
        }
        return null;
    }

    private NetworkParameters paramsForEnvironment(String networkId) {
        if (networkId == null) {
            networkId = NetworkParameters.ID_TESTNET;
        }
        return NetworkParameters.fromID(networkId);
    }

    static class WalletListener extends AbstractWalletEventListener {

        @Override
        public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
            logger.info("received transaction: " + tx.getHashAsString() + " value: " + tx.getValue(wallet));
        }

        @Override
        public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
            logger.info("sent transaction: " + tx.getHashAsString() + " new balance: " + newBalance.toFriendlyString() + "BTC");
        }
    }
}
