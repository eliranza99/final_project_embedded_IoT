package com.example.udpbridge

import java.security.SecureRandom

object SecurityConfigState {

    // מפתח קצר – 6 תווים, אבל רנדומי ומשתנה
    private const val KEY_LENGTH = 6

    private val charset: List<Char> =
        ("ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "abcdefghijklmnopqrstuvwxyz" +
                "0123456789").toList()

    var secretKey: String = generateRandomKey()
        private set

    /**
     * יוצר מפתח חדש ושומר אותו כ-secretKey הנוכחי.
     * מחזיר את המפתח כדי שה-UI יוכל להתעדכן.
     */
    fun regenerateSecretKey(): String {
        secretKey = generateRandomKey()
        return secretKey
    }

    private fun generateRandomKey(length: Int = KEY_LENGTH): String {
        val random = SecureRandom()
        val chars = CharArray(length) {
            charset[random.nextInt(charset.size)]
        }
        return String(chars)
    }
}
