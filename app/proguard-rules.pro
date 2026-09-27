# Secure Browser ProGuard rules.
# Minification is disabled for Phase 1 to keep builds deterministic.
# When enabling R8 later, keep Room entities and WebView glue:
# -keep class com.securebrowser.app.data.db.entity.** { *; }
# -keepclassmembers class * extends androidx.room.RoomDatabase { *; }
