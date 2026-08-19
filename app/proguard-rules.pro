# ProGuard rules for SwiftDock Android App

# Keep data models
-keep class com.example.swiftdock.ShortcutButton { *; }
-keep class com.example.swiftdock.NetworkClient$ProfileInfo { *; }

# Keep androidx viewbinding & navigation
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

# Preserve serialization
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
