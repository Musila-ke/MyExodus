# SQLCipher native classes must survive R8 when minification is enabled (plan §13.2).
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.zetetic.database.sqlcipher.driver.** { *; }
