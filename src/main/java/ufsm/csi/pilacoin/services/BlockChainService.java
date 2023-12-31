package ufsm.csi.pilacoin.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.SneakyThrows;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import ufsm.csi.pilacoin.blueprint.TypeCommon;
import ufsm.csi.pilacoin.blueprint.TypeGenericStrategy;
import ufsm.csi.pilacoin.model.Block;
import ufsm.csi.pilacoin.model.BlocoValidado;
import ufsm.csi.pilacoin.shared.TimeFormat;
import ufsm.csi.pilacoin.shared.Singleton;

import javax.crypto.Cipher;
import static javax.crypto.Cipher.*;
import static ufsm.csi.pilacoin.config.Config.CONST_NAME;
import static ufsm.csi.pilacoin.config.Config.PROCESSORS;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.*;

@Service
public class BlockChainService implements Runnable, TypeCommon, TypeGenericStrategy {

    // BLOCKMINING SERVICE RELATED
    private final ObjectWriter objectWriter = new ObjectMapper().writer().withDefaultPrettyPrinter();
    private BigInteger difficulty;
    private Block block;
    private Boolean shouldStop = false;

    @Override
    @SneakyThrows
    public void run() {
        this.block.setChaveUsuarioMinerador(this.singleton.getPublicKey().toString().getBytes(UTF_8));

        byte[] bytes = new byte[256 / 8];
        byte[] nonceDigest;

        for (int count = 0; this.shouldStop == false; count++) {
            new Random().nextBytes(bytes);

            nonceDigest = MessageDigest.getInstance("SHA-256").digest(bytes);
            this.block.setNonce(new BigInteger(nonceDigest).abs());

            String json = objectWriter.writeValueAsString(this.block);
            BigInteger hash = new BigInteger(MessageDigest.getInstance("SHA-256").digest(json.getBytes(UTF_8))).abs();

            if (this.difficulty != null && hash.compareTo(this.difficulty) < 0) {
                printBlockFoundMessage(count, json);
                this.rabbitService.send("bloco-minerado", json);
            }
        }

        Thread.currentThread().interrupt(); // Interrupt at loop escape
    }

    public void shouldStop() {
        this.shouldStop = true;
    }

    private void printBlockFoundMessage(int count, String json) {
        System.out.println(TimeFormat.threadName(Thread.currentThread()));
        System.out.println(TimeFormat.blockFoundMessage(count, json));
    }

    @Override
    public <T> void change(T obj) {
        if (obj instanceof Block block)
            this.block = block;
        if (obj instanceof BigInteger big)
            this.difficulty = big;
    }

    // BLOCK SERVICE RELATED
    private final RabbitService rabbitService;

    private Block currentBlock;
    private List<TypeGenericStrategy> observers = new ArrayList<>();

    private boolean miningThreadsStarted = false;
    private final Singleton singleton;
    private final ObjectReader reader = new ObjectMapper().reader();
    private final ObjectWriter writer = new ObjectMapper().writer();

    private final HashChallengeService miningService;

    public BlockChainService(Singleton singleton, RabbitService rabbitService, HashChallengeService miningService) {
        this.miningService = miningService;
        this.rabbitService = rabbitService;
        this.singleton = singleton;
    }

    public void startBlockMiningThreads(int threads) {
        for (int i = 0; i < threads; i++) {
            BlockChainService miningService = new BlockChainService(
                Singleton.getInstance(),
                this.rabbitService,
                this.miningService);

            this.observers.add(miningService);
            this.miningService.hold(miningService);
            miningService.change(this.currentBlock);
            miningService.change(this.miningService.getCurrentDifficulty());

            new Thread(miningService).start();
        }
    }

    @SneakyThrows
    @RabbitListener(queues = { "descobre-bloco" })
    public void findBlocks(@Payload String blockStr) {
        this.currentBlock = this.reader.readValue(blockStr, Block.class);
        if (!this.miningThreadsStarted) {
            this.startBlockMiningThreads(PROCESSORS);
            this.miningThreadsStarted = true;
        }
    }

    @SneakyThrows
    @RabbitListener(queues = { "bloco-minerado" })
    public void validateBlock(@Payload String blockStr) {

        // LISTEN TO MINED BLOCKS AND VALIDATE
        BigInteger hash = new BigInteger(MessageDigest.getInstance("SHA-256").digest(blockStr.getBytes(UTF_8))).abs();
        Block block = this.reader.readValue(blockStr, Block.class);

        BigInteger difficulty = this.miningService.getCurrentDifficulty();

        if (difficulty == null || hash.compareTo(difficulty) >= 0) {
            this.rabbitService.send("bloco-minerado", blockStr);
            return;
        }

        Cipher encryptCipher = Cipher.getInstance("RSA");
        encryptCipher.init(ENCRYPT_MODE, this.singleton.getPrivateKey());
        byte[] hashByteArr = hash.toString().getBytes(UTF_8);

        BlocoValidado blocoValidado = BlocoValidado.builder()
            .nomeValidador(CONST_NAME)
            .bloco(block)
            .assinaturaBloco(encryptCipher.doFinal(hashByteArr))
            .chavePublicaValidador(this.singleton.getPublicKey().toString().getBytes(UTF_8))
            .build();

        String json = this.writer.writeValueAsString(blocoValidado);
        this.rabbitService.send("bloco-validado", json);
    }

    // IMPLEMENTATIONS

    @Override
    public <T> void hold(TypeGenericStrategy object) {
        this.observers.add(object);
    }

    @Override
    public <T> T release(TypeGenericStrategy objects) {
        this.observers.remove(objects);
        throw new UnsupportedOperationException("(BlockService.java) Unimplemented method 'unsubscribe'");
    }
}