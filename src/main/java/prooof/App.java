package prooof;

import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.post;
import static spark.SparkBase.setPort;

import java.io.File;
import java.util.List;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.script.Script;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.kevinsawicki.http.HttpRequest;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

public class App {

    public static void main(String[] args) throws Exception {
        Logger logger = LoggerFactory.getLogger(App.class.getName());
        logger.info("Nice to see you!");

        String environment = System.getenv("BITCOIN_NETWORK");
        if (environment == null) {
            environment = "org.bitcoin.test";
        }
        logger.info("using network " + environment);

        String walletPath = System.getenv("WALLET_PATH");
        if (walletPath == null) {
            walletPath = "./main.wallet";
        }
        logger.info("loading wallet from: " + walletPath);

        String rootDir = System.getenv("ROOT_DIR");
        if (rootDir == null) {
            rootDir = "./";
        }
        logger.info("using root directory: " + rootDir);

        String localhost = System.getenv("CONNECT_TO_LOCALHOST");
        boolean useLocalhost = localhost != null && localhost.equals("1");

        final Treasury treasury = new Treasury(environment, new File(rootDir), useLocalhost);
        treasury.loadWalletFromFile(new File(walletPath));
        treasury.start();

        logger.info("currenct receive address: " + treasury.wallet.currentReceiveAddress());

        String port = System.getenv("PORT");
        if (port != null) {
            setPort(Integer.parseInt(port));
        }

        before("/*", (request, response) -> {
            logger.info(request.requestMethod() + " " + request.pathInfo() + " ip=" + request.ip());
        });

        get("/", (req, res) -> {
            return "ping " + new java.util.Date().getTime();
        });

        get("/api/wallet", (req, res) -> {
            JSONObject wallet = new JSONObject();
            wallet.put("freshReceiveAddress", treasury.wallet.freshReceiveAddress());
            wallet.put("balance", treasury.wallet.getBalance().getValue());
            wallet.put("balanceBtc", treasury.wallet.getBalance().toFriendlyString());
            wallet.put("transactions", treasury.wallet.getTransactions(false).size());

            res.type("application/json");
            return wallet.toJSONString();
        });

        post("/api/sign", (req, res) -> {
            String data = req.queryParams("data");
            String notificationUrl = req.queryParams("notification_url");
            logger.info("data: " + data + " notification_url: " + notificationUrl);
            JSONObject responseJson = new JSONObject();
            try {
                Wallet.SendRequest sendRequest = treasury.createAndPublishOpReturn(data);
                if (notificationUrl != null && notificationUrl != "") {
                    logger.info("registering future callback");
                    Futures.addCallback(sendRequest.tx.getConfidence().getDepthFuture(1), new FutureCallback<Transaction>() {
                        @Override
                        public void onSuccess(Transaction result) {
                            logger.info("one confirmation for " + result.getHashAsString());
                            HttpRequest request = HttpRequest.post(notificationUrl);
                            request.header("Content-Type", "application/json");
                            JSONObject txJson = new JSONObject();
                            txJson.put("hash", result.getHashAsString());
                            txJson.put("confidence", result.getConfidence().getDepthInBlocks());
                            txJson.put("appearsIn", result.getConfidence().getAppearedAtChainHeight());
                            request.send(txJson.toJSONString());
                            if (request.ok()) {
                                logger.info("notification successful for transaction " + result.getHashAsString());
                            } else {
                                logger.info("notification failed for transaction " + result.getHashAsString());
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            // This kind of future can't fail, just rethrow in case something very weird happens.
                            throw new RuntimeException(t);
                        }
                    });
                }
                responseJson.put("hash", sendRequest.tx.getHashAsString());
            } catch (Exception e) {
                logger.error("insufficient money", e);
                responseJson.put("error", "enough money?");
                res.status(422);
            }
            res.type("application/json");
            return responseJson.toJSONString();
        });

        post("/api/validate/:txHash", (req, res) -> {
            String expectedData = req.queryParams("data");
            String txHash = req.params("txHash");
            logger.info("validating " + txHash);
            Transaction tx;
            try {
                tx = treasury.fetchTransaction(txHash);
                List<TransactionOutput> outputs = tx.getOutputs();
                for (TransactionOutput output : outputs) {
                    Script script = output.getScriptPubKey();
                    if (script.isOpReturn()) {
                        byte[] hexData = script.getChunks().get(0).data;
                        String hexDataStr = new String(hexData);

                        byte[] txData = BaseEncoding.base32Hex().decode(hexDataStr);
                        String txDataStr = new String(txData);

                        logger.info("hexDataStr" + hexDataStr);
                        logger.info("txDataStr" + txDataStr);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("error getting the transaction", e);
            }

            res.type("application/json");
            return "{}";
        });
    }
}
