package com.nspawnmgr.crypto;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real bug: sshj 0.40.0's PKCS8 PEM parser doesn't support Ed25519 at all
 * ("PKCS8 Private Key Algorithm [1.3.101.112] not supported"). SSHClient.loadKeys() is lazy, so
 * that failure only surfaced once RealContainerReadinessChecker's authPublickey() actually needed
 * the key material - by which point it just looked like "UserAuthException: Exhausted available
 * authentication methods", with no indication the private key format itself was the problem. This
 * was the actual root cause of a container never reaching RUNNING, independent of (and hiding
 * behind) several other real, separately-fixed issues that session (NAT hairpin exclusion, a host
 * firewall default-reject policy, world-writable SSH host keys, sshd's StrictModes, and a
 * provisioning step that silently swallowed command failures). Generating the private key in
 * OpenSSH's own wire format instead of PKCS8 is what actually fixed it - this test exists so a
 * future refactor of SshKeyPairGenerator can't quietly regress back to an unparseable format
 * without a unit test catching it immediately, rather than only failing during a live SSH handshake.
 */
class SshKeyPairGeneratorTest {

    @Test
    void generatedPrivateKeyIsParseableBySshjAndMatchesTheGeneratedPublicKey() throws Exception {
        SshKeyPairGenerator.GeneratedKeyPair pair = new SshKeyPairGenerator().generate();

        assertThat(pair.privateKeyPem()).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");

        SSHClient ssh = new SSHClient();
        KeyProvider loaded = ssh.loadKeys(pair.privateKeyPem(), null, (net.schmizz.sshj.userauth.password.PasswordFinder) null);
        PublicKey loadedPublic = loaded.getPublic();

        assertThat(toOpenSsh(loadedPublic)).isEqualTo(pair.publicKeyOpenSsh());
    }

    private String toOpenSsh(PublicKey publicKey) throws Exception {
        EdECPublicKey ed = (EdECPublicKey) publicKey;
        NamedParameterSpec params = (NamedParameterSpec) ed.getParams();
        assertThat(params.getName()).isEqualToIgnoringCase("Ed25519");
        byte[] encoded = ed.getEncoded();
        byte[] rawPoint = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, rawPoint, 0, 32);
        Buffer.PlainBuffer buffer = new Buffer.PlainBuffer();
        buffer.putString("ssh-ed25519");
        buffer.putBytes(rawPoint);
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(buffer.getCompactData()) + " nspawnmgr";
    }
}
