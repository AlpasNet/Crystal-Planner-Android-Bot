package net.alpas.crystalplanner.storage;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.discord.DiscordToken;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureTokenStore {
    private static final String PREFS = "crystal_planner_secret";
    private static final String KEY_ALIAS = "crystal_planner_bot_token";
    private static final String VALUE_IV = "token_iv";
    private static final String VALUE_DATA = "token_data";

    private final Context context;

    public SecureTokenStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public void save(String token) throws Exception {
        String normalized = DiscordToken.normalize(token);
        if (normalized.isEmpty()) {
            clear();
            return;
        }
        DiscordToken.requirePlausible(
                normalized,
                context.getString(R.string.discord_token_empty),
                context.getString(R.string.discord_token_too_short),
                context.getString(R.string.discord_token_whitespace)
        );

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(VALUE_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(VALUE_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    public String load() throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ivText = p.getString(VALUE_IV, null);
        String dataText = p.getString(VALUE_DATA, null);
        if (ivText == null || dataText == null) {
            return "";
        }

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (key == null) {
            clear();
            return "";
        }

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
        );
        byte[] clear = cipher.doFinal(Base64.decode(dataText, Base64.NO_WRAP));
        return new String(clear, StandardCharsets.UTF_8);
    }

    public boolean hasToken() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(VALUE_IV) && p.contains(VALUE_DATA);
    }

    public void clear() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(VALUE_IV)
                .remove(VALUE_DATA)
                .apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
