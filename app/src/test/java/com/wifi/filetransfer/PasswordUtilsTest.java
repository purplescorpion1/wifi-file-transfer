package com.wifi.filetransfer;

import org.junit.Assert;
import org.junit.Test;

public class PasswordUtilsTest {

    @Test
    public void testPasswordHashing() {
        String password = "super_secure_password_123";
        String salt = PasswordUtils.generateSalt();
        
        // Ensure salt is generated and has length
        Assert.assertNotNull(salt);
        Assert.assertFalse(salt.trim().isEmpty());
        
        // Hash password
        String hash1 = PasswordUtils.hashPassword(password, salt);
        String hash2 = PasswordUtils.hashPassword(password, salt);
        
        // Ensure consistent hashing
        Assert.assertEquals(hash1, hash2);
        
        // Ensure different salts yield different hashes
        String salt2 = PasswordUtils.generateSalt();
        String hash3 = PasswordUtils.hashPassword(password, salt2);
        Assert.assertNotEquals(hash1, hash3);
    }
}
