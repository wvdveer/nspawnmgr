package com.nspawnmgr.crypto;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.EdECPublicKey;
import java.nio.charset.StandardCharsets;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;

/** Generates an Ed25519 keypair per container using only the JDK (no BouncyCastle needed). */
@Component
public class SshKeyPairGenerator {

    public record GeneratedKeyPair(String privateKeyPem, String publicKeyOpenSsh) {
    }

    public GeneratedKeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            byte[] rawPoint = rawPublicPoint((EdECPublicKey) keyPair.getPublic());
            byte[] rawSeed = rawPrivateSeed(keyPair.getPrivate().getEncoded());
            String publicKeyOpenSsh = toOpenSshPublicKey(rawPoint);
            String privateKeyPem = toOpenSshPrivateKeyPem(rawPoint, rawSeed);
            return new GeneratedKeyPair(privateKeyPem, publicKeyOpenSsh);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not supported by this JVM", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode generated Ed25519 keypair", e);
        }
    }

    /**
     * sshj 0.40.0's PKCS8 PEM parser doesn't support Ed25519 at all (throws
     * "PKCS8 Private Key Algorithm [1.3.101.112] not supported") - confirmed live, and confirmed
     * to be the actual root cause of every "UserAuthException: Exhausted available authentication
     * methods" seen this session, independent of every other real fix (NAT, firewall, host keys,
     * StrictModes, silently-swallowed provisioning failures): loadKeys() is lazy, so the parse
     * failure only surfaces once authPublickey() actually needs the key material, by which point
     * it just looks like "no usable auth method" rather than a clear parse error. OpenSSH's own
     * private-key wire format (RFC-less, but documented in OpenSSH's PROTOCOL.key) is what sshj's
     * Ed25519 support actually understands, so write that instead of PKCS8.
     */
    private String toOpenSshPrivateKeyPem(byte[] rawPoint, byte[] rawSeed) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        writeSshString(buffer, "none".getBytes(StandardCharsets.US_ASCII)); // ciphername
        writeSshString(buffer, "none".getBytes(StandardCharsets.US_ASCII)); // kdfname
        writeSshString(buffer, new byte[0]); // kdfoptions
        writeUint32(buffer, 1); // number of keys

        ByteArrayOutputStream pubKeyBlob = new ByteArrayOutputStream();
        writeSshString(pubKeyBlob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        writeSshString(pubKeyBlob, rawPoint);
        writeSshString(buffer, pubKeyBlob.toByteArray());

        ByteArrayOutputStream privateSection = new ByteArrayOutputStream();
        int checkint = new java.security.SecureRandom().nextInt();
        writeUint32(privateSection, checkint);
        writeUint32(privateSection, checkint);
        writeSshString(privateSection, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        writeSshString(privateSection, rawPoint);
        // OpenSSH stores the Ed25519 "private key" as seed || public point (64 bytes), not the
        // bare 32-byte seed alone.
        ByteArrayOutputStream privKeyAndPoint = new ByteArrayOutputStream();
        privKeyAndPoint.write(rawSeed);
        privKeyAndPoint.write(rawPoint);
        writeSshString(privateSection, privKeyAndPoint.toByteArray());
        writeSshString(privateSection, "".getBytes(StandardCharsets.US_ASCII)); // comment
        byte padByte = 1;
        while (privateSection.size() % 8 != 0) {
            privateSection.write(padByte++);
        }
        writeSshString(buffer, privateSection.toByteArray());

        String base64 = Base64.getMimeEncoder(70, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(buffer.toByteArray());
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + base64 + "\n-----END OPENSSH PRIVATE KEY-----\n";
    }

    private byte[] rawPrivateSeed(byte[] pkcs8Encoded) {
        // Ed25519 PKCS8: the innermost OCTET STRING content is exactly the raw 32-byte seed, and
        // (confirmed via the DER structure) it's always the trailing 32 bytes of the whole
        // encoding - the same trick already used for the public key's raw point below.
        byte[] seed = new byte[32];
        System.arraycopy(pkcs8Encoded, pkcs8Encoded.length - 32, seed, 0, 32);
        return seed;
    }

    private void writeUint32(ByteArrayOutputStream buffer, int value) {
        buffer.write((value >>> 24) & 0xFF);
        buffer.write((value >>> 16) & 0xFF);
        buffer.write((value >>> 8) & 0xFF);
        buffer.write(value & 0xFF);
    }

    private byte[] rawPublicPoint(EdECPublicKey publicKey) {
        // NamedParameterSpec confirms this is an Ed25519 key; the raw 32-byte point comes from
        // the encoded X.509 SubjectPublicKeyInfo, whose last 32 bytes are the raw Ed25519 point.
        NamedParameterSpec params = (NamedParameterSpec) publicKey.getParams();
        if (!"Ed25519".equalsIgnoreCase(params.getName())) {
            throw new IllegalStateException("Expected Ed25519 key, got " + params.getName());
        }
        byte[] encoded = publicKey.getEncoded();
        byte[] rawPoint = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, rawPoint, 0, 32);
        return rawPoint;
    }

    private String toOpenSshPublicKey(byte[] rawPoint) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        writeSshString(buffer, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
        writeSshString(buffer, rawPoint);
        String base64 = Base64.getEncoder().encodeToString(buffer.toByteArray());
        return "ssh-ed25519 " + base64 + " nspawnmgr";
    }

    private void writeSshString(ByteArrayOutputStream buffer, byte[] data) throws IOException {
        int length = data.length;
        buffer.write((length >>> 24) & 0xFF);
        buffer.write((length >>> 16) & 0xFF);
        buffer.write((length >>> 8) & 0xFF);
        buffer.write(length & 0xFF);
        buffer.write(data);
    }
}
