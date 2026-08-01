package com.wifi.filetransfer;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class PasswordUtils {

    private static final String PREFS_NAME = "wifi_transfer_prefs";
    private static final String KEY_PASSWORD_HASH = "password_hash";
    private static final String KEY_PASSWORD_SALT = "password_salt";
    private static final String KEY_PASSWORD_ENABLED = "password_enabled";

    /**
     * Hash the password with a given salt using SHA-256.
     */
    public static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = password + salt;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * Generate a random salt string.
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : saltBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Save the password hash and salt to SharedPreferences.
     */
    public static void savePassword(Context context, String password, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        editor.putBoolean(KEY_PASSWORD_ENABLED, enabled);

        if (password != null && !password.trim().isEmpty()) {
            String salt = generateSalt();
            String hash = hashPassword(password, salt);
            editor.putString(KEY_PASSWORD_HASH, hash);
            editor.putString(KEY_PASSWORD_SALT, salt);
        }

        editor.apply();
    }

    /**
     * Check if password protection is enabled.
     */
    public static boolean isPasswordEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PASSWORD_ENABLED, false);
    }

    /**
     * Set whether password protection is enabled without changing the password.
     */
    public static void setPasswordEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PASSWORD_ENABLED, enabled).apply();
    }

    /**
     * Verify if the input password matches the stored password.
     */
    public static boolean verifyPassword(Context context, String inputPassword) {
        if (!isPasswordEnabled(context)) {
            return true; // No password protection is set
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String storedHash = prefs.getString(KEY_PASSWORD_HASH, null);
        String storedSalt = prefs.getString(KEY_PASSWORD_SALT, null);
        
        if (storedHash == null || storedSalt == null) {
            // Password enabled but no password saved yet. By default, empty password.
            return inputPassword == null || inputPassword.isEmpty();
        }
        
        String inputHash = hashPassword(inputPassword, storedSalt);
        return storedHash.equals(inputHash);
    }

    /**
     * Check if a password hash exists.
     */
    public static boolean hasSavedPassword(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PASSWORD_HASH, null) != null;
    }
}
