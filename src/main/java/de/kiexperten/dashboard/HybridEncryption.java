package de.kiexperten.dashboard;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Hybrid encryption for a client-supplied RSA public key (WebCrypto RSA-OAEP,
 * SHA-256). RSA cannot encrypt larger payloads, so the data is encrypted with
 * a random AES-256-GCM key, and only that key is encrypted with RSA.
 */
public final class HybridEncryption {
	private static final int MIN_RSA_BITS = 2048;
	private static final int GCM_IV_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** All values are Base64; data is ciphertext followed by the GCM tag. */
	public record Encrypted(String key, String iv, String data) {
	}

	private HybridEncryption() {
	}

	public static Encrypted encrypt(final String publicKeyBase64, final byte[] plain)
			throws GeneralSecurityException {
		final PublicKey publicKey = KeyFactory.getInstance("RSA")
				.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
		if (!(publicKey instanceof final RSAPublicKey rsa) || rsa.getModulus().bitLength() < MIN_RSA_BITS)
			throw new GeneralSecurityException("RSA public key of at least " + MIN_RSA_BITS + " bits required");

		final KeyGenerator generator = KeyGenerator.getInstance("AES");
		generator.init(256);
		final SecretKey aesKey = generator.generateKey();
		final byte[] iv = new byte[GCM_IV_BYTES];
		RANDOM.nextBytes(iv);

		final Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
		aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
		final byte[] data = aes.doFinal(plain);

		final Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
		rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec("SHA-256", "MGF1",
				MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
		final byte[] key = rsaCipher.doFinal(aesKey.getEncoded());

		final Base64.Encoder encoder = Base64.getEncoder();
		return new Encrypted(encoder.encodeToString(key), encoder.encodeToString(iv), encoder.encodeToString(data));
	}
}
