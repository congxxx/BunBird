package io.bunbird;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.core.env.Environment;

/** AES-GCM envelopes; the independent master key is kept outside the database. */
final class AccountVault {
    private final Environment env;
    private byte[] key;
    AccountVault(Environment env) { this.env=env; }
    private synchronized byte[] key() throws Exception {
        if(key!=null) return key;
        String configured=env.getProperty("BUNBIRD_ACCOUNT_MASTER_KEY","");
        byte[] material;
        if(!configured.isBlank()) material=Base64.getDecoder().decode(configured);
        else {
            Path path=Path.of(env.getProperty("BUNBIRD_ACCOUNT_KEY_FILE","data/account-vault.key")).toAbsolutePath();
            Files.createDirectories(path.getParent());
            if(!Files.exists(path)) {
                byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);
                try {
                    try { Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                    catch(UnsupportedOperationException e) { Files.createFile(path); }
                    Files.writeString(path,Base64.getEncoder().encodeToString(generated));
                } catch(FileAlreadyExistsException ignored) {}
            }
            material=Base64.getDecoder().decode(Files.readString(path).trim());
        }
        if(material.length!=32) throw new IllegalStateException();
        key=material;return key;
    }
    String encrypt(String secret) {
        try {
            byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException("Account credential encryption unavailable"); }
    }
    String decrypt(String envelope) {
        try {
            var parts=envelope.split(":",2);
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key(),"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),java.nio.charset.StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Account credential decryption unavailable"); }
    }
}
