# Release rules for the Assistant_App.

# Strip every android.util.Log call from release builds, so no log line can carry
# Message_Text or other user data even if one slips past SafeLog (Requirement 3.5).
-assumenosideeffects class android.util.Log {
    *;
}

# kotlinx.serialization keeps its generated serializers through its own consumer rules.
# Room, Hilt and WorkManager ship consumer rules as well; add project rules below only
# when a release build shows a concrete missing class.
