package freenet.crypt;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.OCBBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public class AEADInputStream extends FilterInputStream {
    
    private static final int MAC_SIZE = AEADOutputStream.MAC_SIZE;
    private final AEADBlockCipher cipher;
    
    /** Create a decrypting, authenticating InputStream. IMPORTANT: We only authenticate when 
     * closing the stream, so do NOT use Closer.close() etc and swallow IOException's on close(),
     * as that's what we will throw if authentication fails. We will read the nonce from the 
     * stream; it functions similarly to an IV.
     * @param is The underlying InputStream. 
     * @param key The encryption key.
     * @param nonce The nonce. This serves the function of an IV. As a nonce, this MUST be unique. 
     * We will write it to the stream so the other side can pick it up, like an IV. 
     * @param mainCipher The BlockCipher for encrypting data. E.g. AES; not a block mode. This will
     * be used for encrypting a fairly large amount of data so could be any of the 3 BC AES impl's.
     * @param hashCipher The BlockCipher for the final hash. E.g. AES, not a block mode. This will
     * not be used very much so should be e.g. an AESLightEngine. */
    public AEADInputStream(InputStream is, byte[] key, BlockCipher hashCipher, 
            BlockCipher mainCipher) throws IOException {
        super(is);
        byte[] nonce = new byte[mainCipher.getBlockSize()];
        new DataInputStream(is).readFully(nonce);
        cipher = new OCBBlockCipher(hashCipher, mainCipher);
        KeyParameter keyParam = new KeyParameter(key);
        AEADParameters params = new AEADParameters(keyParam, MAC_SIZE, nonce);
        cipher.init(false, params);
        excess = new byte[mainCipher.getBlockSize()];
        excessEnd = 0;
        excessPtr = 0;
    }
    
    public final int getIVSize() {
        return cipher.getUnderlyingCipher().getBlockSize() / 8;
    }
    
    private final byte[] onebyte = new byte[1];
    
    private final byte[] excess;
    private int excessEnd;
    private int excessPtr;
    
    public int read() throws IOException {
        int length = read(onebyte);
        if(length <= 0) return -1;
        else return onebyte[0];
    }
    
    public int read(byte[] buf) throws IOException {
        return read(buf, 0, buf.length);
    }
    
    public int read(byte[] buf, int offset, int length) throws IOException {
        if(excessEnd != 0) {
            length = Math.min(length, excessEnd - excessPtr);
            if(length > 0) {
                System.arraycopy(excess, excessPtr, buf, offset, length);
                excessPtr += length;
                if(excessEnd == excessPtr) {
                    excessEnd = 0;
                    excessPtr = 0;
                }
                return length;
            }
        }
        while(true) {
            byte[] temp = new byte[length];
            int read = in.read(temp);
            if(read <= 0) return read;
            assert(read <= length);
            int outLength = cipher.getUpdateOutputSize(read);
            if(outLength > length) {
                byte[] outputTemp = new byte[outLength];
                int decryptedBytes = cipher.processBytes(temp, 0, read, outputTemp, 0);
                assert(decryptedBytes == outLength);
                System.arraycopy(outputTemp, 0, buf, offset, length);
                excessEnd = outLength - length;
                assert(excessEnd < excess.length);
                System.arraycopy(outputTemp, length, excess, 0, excessEnd);
                return length;
            } else {
                int decryptedBytes = cipher.processBytes(temp, 0, read, buf, offset);
                if(decryptedBytes > 0) return decryptedBytes;
            }
        }
    }
    
    public void close() throws IOException {
        byte[] tag = new byte[cipher.getOutputSize(0)];
        new DataInputStream(in).readFully(tag);
        try {
            cipher.doFinal(tag, 0);
        } catch (InvalidCipherTextException e) {
            throw new AEADVerificationFailedException();
        }
        in.close();
    }
    
    public static AEADInputStream createAES(InputStream is, byte[] key) throws IOException {
        AESEngine mainCipher = new AESEngine();
        AESLightEngine hashCipher = new AESLightEngine();
        return new AEADInputStream(is, key, hashCipher, mainCipher);
    }

}
