package net.tref.xraytunnel;

import android.content.Context;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SshKeyStore {
    private static final String KEY_DIR = "ssh-keys";
    private static final String PRIVATE_SUFFIX = ".key";
    private static final String PUBLIC_SUFFIX = ".pub";
    private static final int RSA_KEY_SIZE = 4096;

    private SshKeyStore() {
    }

    static synchronized byte[] privateKey(Context context, TunnelProfile profile)
            throws IOException, JSchException {
        ensureKeyPair(context, profile);
        return readFile(privateKeyFile(context, profile));
    }

    static synchronized String publicKey(Context context, TunnelProfile profile)
            throws IOException, JSchException {
        ensureKeyPair(context, profile);
        return new String(readFile(publicKeyFile(context, profile)), StandardCharsets.UTF_8).trim();
    }

    static synchronized String authorizedKeys(Context context) throws IOException, JSchException {
        StringBuilder builder = new StringBuilder();
        for (TunnelProfile profile : TunnelConfig.PROFILES) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(publicKey(context, profile));
        }
        return builder.toString();
    }

    private static void ensureKeyPair(Context context, TunnelProfile profile)
            throws IOException, JSchException {
        File privateKey = privateKeyFile(context, profile);
        File publicKey = publicKeyFile(context, profile);
        if (privateKey.isFile() && publicKey.isFile()) {
            return;
        }

        File dir = keyDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create SSH key directory");
        }

        KeyPair keyPair = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, RSA_KEY_SIZE);
        try {
            String comment = "xray-ssh-tunnel-" + profile.name;
            keyPair.setPublicKeyComment(comment);

            ByteArrayOutputStream privateOut = new ByteArrayOutputStream();
            keyPair.writePrivateKey(privateOut);
            writeFile(privateKey, privateOut.toByteArray());

            ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
            keyPair.writePublicKey(publicOut, comment);
            writeFile(publicKey, publicOut.toByteArray());
        } finally {
            keyPair.dispose();
        }
    }

    private static File keyDir(Context context) {
        return new File(context.getFilesDir(), KEY_DIR);
    }

    private static File privateKeyFile(Context context, TunnelProfile profile) {
        return new File(keyDir(context), profile.name + PRIVATE_SUFFIX);
    }

    private static File publicKeyFile(Context context, TunnelProfile profile) {
        return new File(keyDir(context), profile.name + PUBLIC_SUFFIX);
    }

    private static byte[] readFile(File file) throws IOException {
        long length = file.length();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("SSH key file is too large");
        }

        byte[] data = new byte[(int) length];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
            if (offset != data.length) {
                throw new IOException("Could not read complete SSH key file");
            }
        }
        return data;
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
        }
    }
}
