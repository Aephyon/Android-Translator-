# ML Kit ships its own consumer rules; nothing extra needed.

# Drop debug/verbose logging (it logs message snippets) from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
